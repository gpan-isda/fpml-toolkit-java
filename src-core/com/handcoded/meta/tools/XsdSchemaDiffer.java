package com.handcoded.meta.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * CLI tool that computes a structural delta between two FpML XSD schema
 * directories and writes a {@code candidate-delta.xml} file for human review.
 *
 * <h3>What it detects</h3>
 * <ul>
 *   <li>{@code NEW_ATTRIBUTE}      — attribute present in target but absent in source.</li>
 *   <li>{@code REQUIRED_ATTRIBUTE} — attribute present in both but use changed to
 *       {@code required} in target.</li>
 *   <li>{@code NEW_ELEMENT}        — child element present in target complex type but
 *       absent in source.</li>
 *   <li>{@code REQUIRED_ELEMENT}   — child element present in both but {@code minOccurs}
 *       increased to ≥ 1 in target.</li>
 *   <li>{@code DROPPED_ATTRIBUTE} / {@code DROPPED_ELEMENT} — present in source but
 *       absent in target (informational).</li>
 * </ul>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Recursively collect all XSD files for each schema directory (following
 *       {@code xs:include}).</li>
 *   <li>Parse all {@code xs:complexType} definitions into a flat map of
 *       {@code typeName → TypeDef}.</li>
 *   <li>Resolve {@code xs:extension} inheritance chains to produce a merged view
 *       of all attributes and child elements per type.</li>
 *   <li>Diff the two flat maps and emit {@code Candidate} records.</li>
 *   <li>Generate a suggested XPath for each candidate using a reverse mapping of
 *       {@code typeName → globalElementNames}.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>
 *   java com.handcoded.meta.tools.XsdSchemaDiffer \
 *        files-fpml/schemas/fpml5-12/confirmation \
 *        files-fpml/schemas/fpml5-13/confirmation \
 *        5-12 5-13 confirmation \
 *        files-fpml/conversion-profiles/candidate-5-12-to-5-13-confirmation.xml
 * </pre>
 *
 * @author ISDA FpML Team
 * @since  TFP 1.x
 */
public final class XsdSchemaDiffer {

    private static final Logger LOG =
            Logger.getLogger(XsdSchemaDiffer.class.getName());

    /** W3C XML Schema namespace. */
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    /** Candidate-delta namespace. */
    private static final String CD_NS  = "http://www.handcoded.com/fpml/candidate-delta";

    // =========================================================================
    // main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println(
                "Usage: XsdSchemaDiffer [--detect-renames] <fromSchemaDir> <toSchemaDir> "
              + "<fromVersion> <toVersion> <view> <outputCandidateDelta>");
            System.exit(2);
        }

        boolean detectRenames = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if ("--detect-renames".equals(arg)) detectRenames = true;
            else positional.add(arg);
        }
        if (positional.size() < 6) {
            System.err.println("Missing required positional arguments (need 6).");
            System.exit(2);
        }

        Path fromDir  = Paths.get(positional.get(0));
        Path toDir    = Paths.get(positional.get(1));
        String fromVer = positional.get(2);
        String toVer   = positional.get(3);
        String view    = positional.get(4);
        Path output    = Paths.get(positional.get(5));

        if (!Files.isDirectory(fromDir)) { System.err.println("Not a directory: " + fromDir); System.exit(3); }
        if (!Files.isDirectory(toDir))   { System.err.println("Not a directory: " + toDir);   System.exit(3); }

        System.out.println("Parsing FROM schema: " + fromDir);
        SchemaModel fromModel = parseSchemaDir(fromDir);
        System.out.println("  => " + fromModel.complexTypes.size() + " complex types");
        if (fromModel.targetNamespace != null)
            System.out.println("  => targetNamespace: " + fromModel.targetNamespace);

        System.out.println("Parsing TO schema:   " + toDir);
        SchemaModel toModel = parseSchemaDir(toDir);
        System.out.println("  => " + toModel.complexTypes.size() + " complex types");
        if (toModel.targetNamespace != null)
            System.out.println("  => targetNamespace: " + toModel.targetNamespace);

        System.out.println("Computing delta…");
        List<Candidate> candidates = computeDelta(fromModel, toModel, detectRenames);
        System.out.println("  => " + candidates.size() + " candidate(s) found");

        writeCandidateDelta(candidates, fromVer, toVer, view, fromModel, toModel, output);
        System.out.println("Written: " + output);
    }

    // =========================================================================
    // Data model
    // =========================================================================

    /** Flat model of all complex types extracted from a schema directory. */
    static final class SchemaModel {
        /** typeName → TypeDef (merged with extension base chain). */
        final Map<String, TypeDef> complexTypes = new LinkedHashMap<>();
        /** elementName → typeName (from global xs:element declarations). */
        final Map<String, String>  elementTypes  = new LinkedHashMap<>();
        /** typeName → list of global element names that use this type. */
        final Map<String, List<String>> typeToElements = new LinkedHashMap<>();
        /** typeName → Path of the XSD file that first defined it. */
        final Map<String, Path> typeToFile = new LinkedHashMap<>();
        /** The FpML targetNamespace discovered from the root XSD schema element. */
        String targetNamespace;
        /** The root schema directory path. */
        Path   schemaDir;
    }

    static final class TypeDef {
        final String name;
        String baseName; // xs:extension/@base (unqualified)
        /** attrName → AttrDef (own attributes, before base-chain merge). */
        final Map<String, AttrDef>  attributes = new LinkedHashMap<>();
        /** elemName → ElemDef (own child elements, before base-chain merge). */
        final Map<String, ElemDef>  elements   = new LinkedHashMap<>();

        TypeDef(String name) { this.name = name; }
    }

    static final class AttrDef {
        final String name;
        final String use;          // required, optional, prohibited
        final String defaultValue; // may be null
        final String type;

        AttrDef(String name, String use, String defaultValue, String type) {
            this.name         = name;
            this.use          = use;
            this.defaultValue = defaultValue;
            this.type         = type;
        }
    }

    static final class ElemDef {
        final String name;
        final int    minOccurs;
        final String type;

        ElemDef(String name, int minOccurs, String type) {
            this.name      = name;
            this.minOccurs = minOccurs;
            this.type      = type;
        }
    }

    /** A detected difference between the two schema versions. */
    static final class Candidate {
        enum ChangeType {
            NEW_ATTRIBUTE, REQUIRED_ATTRIBUTE, DROPPED_ATTRIBUTE,
            NEW_ELEMENT,   REQUIRED_ELEMENT,   DROPPED_ELEMENT,
            RENAMED_ATTRIBUTE, RENAMED_ELEMENT
        }

        final ChangeType changeType;
        final String     typeName;
        final String     nodeName;       // attribute or element name (for RENAMED_*, this is the SOURCE name)
        /** Constraint in the TARGET schema (use or minOccurs string). */
        final String     targetConstraint;
        final String     schemaDefault;  // XSD default value (may be null)
        /** Heuristic XPath (to be verified/corrected by reviewer). */
        final String     suggestedXpath;
        /** Schema-required or schema-optional. */
        final String     constraintType;
        /** For RENAMED_*: the suggested new name in the target schema. May be null. */
        final String     suggestedName;

        Candidate(ChangeType changeType, String typeName, String nodeName,
                  String targetConstraint, String schemaDefault,
                  String suggestedXpath, String constraintType) {
            this(changeType, typeName, nodeName, targetConstraint, schemaDefault,
                 suggestedXpath, constraintType, null);
        }

        Candidate(ChangeType changeType, String typeName, String nodeName,
                  String targetConstraint, String schemaDefault,
                  String suggestedXpath, String constraintType, String suggestedName) {
            this.changeType       = changeType;
            this.typeName         = typeName;
            this.nodeName         = nodeName;
            this.targetConstraint = targetConstraint;
            this.schemaDefault    = schemaDefault;
            this.suggestedXpath   = suggestedXpath;
            this.constraintType   = constraintType;
            this.suggestedName    = suggestedName;
        }
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    static SchemaModel parseSchemaDir(Path schemaDir) throws Exception {
        DocumentBuilder db = newBuilder();
        SchemaModel model  = new SchemaModel();
        model.schemaDir = schemaDir.toAbsolutePath().normalize();
        Set<String> visited = new LinkedHashSet<>();

        // Collect all XSD files recursively, starting from the main schema
        Path mainSchema = findMainSchema(schemaDir);
        if (mainSchema == null) {
            // Fall back: process all XSD files in the directory
            try (var stream = Files.walk(schemaDir)) {
                stream.filter(p -> p.toString().toLowerCase().endsWith(".xsd"))
                      .forEach(p -> processXsd(p, schemaDir, db, model, visited));
            }
        } else {
            processXsd(mainSchema, schemaDir, db, model, visited);
        }

        // Build reverse map: typeName → [elementNames]
        for (Map.Entry<String, String> e : model.elementTypes.entrySet()) {
            String elemName = e.getKey();
            String typeName = unqualify(e.getValue());
            model.typeToElements
                 .computeIfAbsent(typeName, k -> new ArrayList<>())
                 .add(elemName);
        }

        // Merge extension chains into a single flat view
        mergeExtensions(model);

        return model;
    }

    private static void processXsd(Path file, Path baseDir, DocumentBuilder db,
                                   SchemaModel model, Set<String> visited) {
        String canonical = file.toAbsolutePath().normalize().toString();
        if (visited.contains(canonical)) return;
        visited.add(canonical);

        Document doc;
        try {
            doc = db.parse(file.toFile());
        } catch (Exception e) {
            LOG.warning("Skipping unreadable XSD: " + file + " — " + e.getMessage());
            return;
        }

        Element schema = doc.getDocumentElement();

        // Capture targetNamespace from the first XSD file that declares it
        if (model.targetNamespace == null) {
            String tns = schema.getAttribute("targetNamespace");
            if (tns != null && !tns.isEmpty())
                model.targetNamespace = tns;
        }

        // Follow xs:include and xs:import
        processIncludes(schema, file.getParent(), db, model, visited);

        // Extract global xs:element declarations (name + type)
        NodeList globalElems = schema.getChildNodes();
        for (int i = 0; i < globalElems.getLength(); i++) {
            Node n = globalElems.item(i);
            if (!isXsdElement(n, "element")) continue;
            Element el = (Element) n;
            String eName = el.getAttribute("name");
            String eType = el.getAttribute("type");
            if (!eName.isEmpty() && !eType.isEmpty())
                model.elementTypes.putIfAbsent(eName, eType);
        }

        // Extract xs:complexType definitions
        NodeList complexTypes = schema.getChildNodes();
        for (int i = 0; i < complexTypes.getLength(); i++) {
            Node n = complexTypes.item(i);
            if (!isXsdElement(n, "complexType")) continue;
            Element ct = (Element) n;
            String typeName = ct.getAttribute("name");
            if (typeName.isEmpty()) continue;
            TypeDef def = model.complexTypes.computeIfAbsent(typeName, TypeDef::new);
            // Record the defining XSD file (first encounter wins)
            model.typeToFile.putIfAbsent(typeName, file.toAbsolutePath().normalize());
            extractTypeContent(ct, def);
        }
    }

    private static void processIncludes(Element schema, Path dir,
                                        DocumentBuilder db, SchemaModel model,
                                        Set<String> visited) {
        NodeList children = schema.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!isXsdElement(n, "include") && !isXsdElement(n, "import")) continue;
            String loc = ((Element) n).getAttribute("schemaLocation");
            if (loc.isEmpty()) continue;
            // Skip external (http) locations
            if (loc.startsWith("http://") || loc.startsWith("https://")) continue;
            Path resolved = dir.resolve(loc).normalize();
            if (Files.exists(resolved))
                processXsd(resolved, dir, db, model, visited);
        }
    }

    /**
     * Extracts attributes and child elements from a xs:complexType element,
     * handling xs:complexContent/xs:extension and xs:sequence/xs:all/xs:choice.
     * Does NOT descend into nested xs:complexType definitions.
     */
    private static void extractTypeContent(Element complexType, TypeDef def) {
        // Look for xs:complexContent or xs:simpleContent first
        Element complexContent = firstChildXsd(complexType, "complexContent");
        Element simpleContent  = firstChildXsd(complexType, "simpleContent");

        Element contentRoot = complexType; // where to collect attributes/elements

        if (complexContent != null) {
            Element extension  = firstChildXsd(complexContent, "extension");
            Element restriction = firstChildXsd(complexContent, "restriction");
            Element target = extension != null ? extension : restriction;
            if (target != null) {
                String base = unqualify(target.getAttribute("base"));
                if (!base.isEmpty() && def.baseName == null) def.baseName = base;
                contentRoot = target;
            }
        } else if (simpleContent != null) {
            Element extension = firstChildXsd(simpleContent, "extension");
            if (extension != null) {
                String base = unqualify(extension.getAttribute("base"));
                if (!base.isEmpty() && def.baseName == null) def.baseName = base;
                contentRoot = extension;
            }
        }

        collectAttributes(contentRoot, def);
        collectChildElements(contentRoot, def);
    }

    /**
     * Recursively collects xs:attribute declarations, stopping at nested
     * xs:complexType boundaries.
     */
    private static void collectAttributes(Element parent, TypeDef def) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            // Stop descent into nested xs:complexType
            if ("complexType".equals(local) || "simpleType".equals(local)) continue;

            if ("attribute".equals(local) && isXsdNs(el)) {
                String attrName = el.getAttribute("name");
                String ref = el.getAttribute("ref");
                if (attrName.isEmpty() && !ref.isEmpty())
                    attrName = unqualify(ref);
                if (attrName.isEmpty()) continue;
                String use   = el.getAttribute("use");
                String defV  = el.getAttribute("default");
                String fixed = el.getAttribute("fixed");
                String type  = el.getAttribute("type");
                if (use.isEmpty()) use = "optional";
                String effectiveDefault = !defV.isEmpty() ? defV : (!fixed.isEmpty() ? fixed : null);
                def.attributes.putIfAbsent(attrName,
                        new AttrDef(attrName, use, effectiveDefault, type));
            } else if (isXsdNs(el)) {
                // Recurse into xs:sequence, xs:all, xs:choice, xs:group,
                //           xs:attributeGroup, xs:extension, xs:restriction
                if ("sequence".equals(local) || "all".equals(local)
                        || "choice".equals(local) || "attributeGroup".equals(local)
                        || "group".equals(local)
                        || "extension".equals(local) || "restriction".equals(local)) {
                    collectAttributes(el, def);
                }
            }
        }
    }

    /**
     * Recursively collects xs:element child declarations, stopping at nested
     * xs:complexType boundaries.
     */
    private static void collectChildElements(Element parent, TypeDef def) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            if ("complexType".equals(local) || "simpleType".equals(local)) continue;

            if ("element".equals(local) && isXsdNs(el)) {
                String elemName = el.getAttribute("name");
                String ref = el.getAttribute("ref");
                if (elemName.isEmpty() && !ref.isEmpty())
                    elemName = unqualify(ref);
                if (elemName.isEmpty()) continue;
                String minOccStr = el.getAttribute("minOccurs");
                int    minOcc    = minOccStr.isEmpty() ? 1 : safeInt(minOccStr, 1);
                String type      = el.getAttribute("type");
                def.elements.putIfAbsent(elemName,
                        new ElemDef(elemName, minOcc, type));
            } else if (isXsdNs(el)) {
                if ("sequence".equals(local) || "all".equals(local)
                        || "choice".equals(local) || "group".equals(local)
                        || "extension".equals(local) || "restriction".equals(local)) {
                    // xs:choice children are all optional even if minOccurs=1 on the choice
                    if ("choice".equals(local)) {
                        collectChildElementsAsOptional(el, def);
                    } else {
                        collectChildElements(el, def);
                    }
                }
            }
        }
    }

    /** Same as collectChildElements but forces minOccurs=0 (inside xs:choice). */
    private static void collectChildElementsAsOptional(Element parent, TypeDef def) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            if ("complexType".equals(local) || "simpleType".equals(local)) continue;
            if ("element".equals(local) && isXsdNs(el)) {
                String elemName = el.getAttribute("name");
                String ref = el.getAttribute("ref");
                if (elemName.isEmpty() && !ref.isEmpty()) elemName = unqualify(ref);
                if (elemName.isEmpty()) continue;
                String type = el.getAttribute("type");
                def.elements.putIfAbsent(elemName, new ElemDef(elemName, 0, type));
            } else if (isXsdNs(el)) {
                collectChildElementsAsOptional(el, def);
            }
        }
    }

    /**
     * Merges extension base chains so each TypeDef has a complete flat view of
     * all inherited attributes and child elements.
     */
    private static void mergeExtensions(SchemaModel model) {
        // We iterate in topological order (best-effort: repeat until stable)
        boolean changed = true;
        int pass = 0;
        while (changed && pass < 20) {
            changed = false;
            pass++;
            for (TypeDef def : model.complexTypes.values()) {
                if (def.baseName == null) continue;
                TypeDef base = model.complexTypes.get(def.baseName);
                if (base == null) continue;
                // Copy base attributes/elements that are NOT already in def
                int before = def.attributes.size() + def.elements.size();
                for (Map.Entry<String, AttrDef> e : base.attributes.entrySet())
                    def.attributes.putIfAbsent(e.getKey(), e.getValue());
                for (Map.Entry<String, ElemDef> e : base.elements.entrySet())
                    def.elements.putIfAbsent(e.getKey(), e.getValue());
                int after = def.attributes.size() + def.elements.size();
                if (after > before) changed = true;
            }
        }
    }

    // =========================================================================
    // Delta computation
    // =========================================================================

    static List<Candidate> computeDelta(SchemaModel from, SchemaModel to) {
        return computeDelta(from, to, false);
    }

    static List<Candidate> computeDelta(SchemaModel from, SchemaModel to, boolean detectRenames) {
        List<Candidate> candidates = new ArrayList<>();

        // Collect DROPPED attrs/elems per type so rename detection can match them
        // typeKey("TYPE","ATTR") → Candidate for DROPPED_ATTRIBUTE
        Map<String, Candidate> droppedAttrs  = new LinkedHashMap<>();
        Map<String, Candidate> droppedElems  = new LinkedHashMap<>();

        for (Map.Entry<String, TypeDef> toEntry : to.complexTypes.entrySet()) {
            String  typeName = toEntry.getKey();
            TypeDef toDef    = toEntry.getValue();
            TypeDef fromDef  = from.complexTypes.get(typeName);

            // --- Attributes ---
            for (AttrDef toAttr : toDef.attributes.values()) {
                String attrName = toAttr.name;
                if (fromDef == null || !fromDef.attributes.containsKey(attrName)) {
                    // NEW attribute
                    String cType = "required".equals(toAttr.use)
                            ? "schema-required" : "schema-optional";
                    candidates.add(new Candidate(
                            Candidate.ChangeType.NEW_ATTRIBUTE, typeName, attrName,
                            toAttr.use, toAttr.defaultValue,
                            suggestAttrXpath(typeName, attrName, to),
                            cType));
                } else {
                    AttrDef fromAttr = fromDef.attributes.get(attrName);
                    if (!"required".equals(fromAttr.use) && "required".equals(toAttr.use)) {
                        // REQUIRED_ATTRIBUTE: optional → required
                        candidates.add(new Candidate(
                                Candidate.ChangeType.REQUIRED_ATTRIBUTE, typeName, attrName,
                                toAttr.use, toAttr.defaultValue,
                                suggestAttrXpath(typeName, attrName, to),
                                "schema-required"));
                    }
                }
            }

            // DROPPED attributes (informational)
            if (fromDef != null) {
                for (String fromAttrName : fromDef.attributes.keySet()) {
                    if (!toDef.attributes.containsKey(fromAttrName)) {
                        Candidate c = new Candidate(
                                Candidate.ChangeType.DROPPED_ATTRIBUTE, typeName, fromAttrName,
                                "n/a", null,
                                suggestAttrXpath(typeName, fromAttrName, from),
                                "dropped");
                        candidates.add(c);
                        droppedAttrs.put(typeName + "|" + fromAttrName, c);
                    }
                }
            }

            // --- Child elements ---
            for (ElemDef toElem : toDef.elements.values()) {
                String elemName = toElem.name;
                if (fromDef == null || !fromDef.elements.containsKey(elemName)) {
                    // NEW element
                    String cType = toElem.minOccurs >= 1
                            ? "schema-required" : "schema-optional";
                    candidates.add(new Candidate(
                            Candidate.ChangeType.NEW_ELEMENT, typeName, elemName,
                            String.valueOf(toElem.minOccurs), null,
                            suggestElemXpath(typeName, elemName, to),
                            cType));
                } else {
                    ElemDef fromElem = fromDef.elements.get(elemName);
                    if (fromElem.minOccurs == 0 && toElem.minOccurs >= 1) {
                        candidates.add(new Candidate(
                                Candidate.ChangeType.REQUIRED_ELEMENT, typeName, elemName,
                                String.valueOf(toElem.minOccurs), null,
                                suggestElemXpath(typeName, elemName, to),
                                "schema-required"));
                    }
                }
            }

            // DROPPED elements (informational)
            if (fromDef != null) {
                for (String fromElemName : fromDef.elements.keySet()) {
                    if (!toDef.elements.containsKey(fromElemName)) {
                        Candidate c = new Candidate(
                                Candidate.ChangeType.DROPPED_ELEMENT, typeName, fromElemName,
                                "n/a", null,
                                suggestElemXpath(typeName, fromElemName, from),
                                "dropped");
                        candidates.add(c);
                        droppedElems.put(typeName + "|" + fromElemName, c);
                    }
                }
            }
        }

        // ── Rename detection (--detect-renames) ──────────────────────────────────
        // For each NEW_ATTRIBUTE / NEW_ELEMENT, look for a DROPPED member in the same
        // type that shares an identical XSD type string (exact match).  When found,
        // replace both entries with a single RENAMED_* candidate.
        if (detectRenames) {
            List<Candidate> toRemove = new ArrayList<>();
            List<Candidate> toAdd    = new ArrayList<>();

            for (Candidate newCand : new ArrayList<>(candidates)) {
                if (newCand.changeType != Candidate.ChangeType.NEW_ATTRIBUTE
                        && newCand.changeType != Candidate.ChangeType.NEW_ELEMENT) continue;

                boolean isAttr = (newCand.changeType == Candidate.ChangeType.NEW_ATTRIBUTE);
                Map<String, Candidate> droppedMap = isAttr ? droppedAttrs : droppedElems;

                // Look for any dropped member in the same type with the same XSD type
                TypeDef toDef   = to.complexTypes.get(newCand.typeName);
                TypeDef fromDef = from.complexTypes.get(newCand.typeName);
                if (toDef == null || fromDef == null) continue;

                String newType = isAttr
                        ? (toDef.attributes.containsKey(newCand.nodeName)
                                ? toDef.attributes.get(newCand.nodeName).type : null)
                        : (toDef.elements.containsKey(newCand.nodeName)
                                ? toDef.elements.get(newCand.nodeName).type : null);
                if (newType == null || newType.isEmpty()) continue;

                for (Map.Entry<String, Candidate> droppedEntry : droppedMap.entrySet()) {
                    Candidate droppedCand = droppedEntry.getValue();
                    if (!droppedCand.typeName.equals(newCand.typeName)) continue;
                    String droppedType = isAttr
                            ? (fromDef.attributes.containsKey(droppedCand.nodeName)
                                    ? fromDef.attributes.get(droppedCand.nodeName).type : null)
                            : (fromDef.elements.containsKey(droppedCand.nodeName)
                                    ? fromDef.elements.get(droppedCand.nodeName).type : null);
                    if (!newType.equals(droppedType)) continue;

                    // Match: promote to RENAMED_*
                    Candidate.ChangeType renamedType = isAttr
                            ? Candidate.ChangeType.RENAMED_ATTRIBUTE
                            : Candidate.ChangeType.RENAMED_ELEMENT;
                    toRemove.add(newCand);
                    toRemove.add(droppedCand);
                    toAdd.add(new Candidate(renamedType, newCand.typeName,
                            droppedCand.nodeName,   // source (old) name
                            newCand.targetConstraint, newCand.schemaDefault,
                            newCand.suggestedXpath, "rename",
                            newCand.nodeName));      // suggestedName = new name
                    break;
                }
            }
            candidates.removeAll(toRemove);
            candidates.addAll(toAdd);
        }

        return candidates;
    }

    // =========================================================================
    // XPath suggestion helpers
    // =========================================================================

    private static String suggestAttrXpath(String typeName, String attrName,
                                           SchemaModel model) {
        List<String> elemNames = model.typeToElements.get(typeName);
        if (elemNames != null && !elemNames.isEmpty()) {
            // Use all matching element names joined as union (first one for brevity)
            String elemName = elemNames.get(0);
            return "//*[local-name()='" + elemName + "']/@" + attrName;
        }
        // Heuristic: camelCase type name → first-lower-case as element name
        String guess = guessElementName(typeName);
        return "//*[local-name()='" + guess + "']/@" + attrName;
    }

    private static String suggestElemXpath(String typeName, String elemName,
                                           SchemaModel model) {
        List<String> elemNames = model.typeToElements.get(typeName);
        if (elemNames != null && !elemNames.isEmpty()) {
            String parentName = elemNames.get(0);
            return "//*[local-name()='" + parentName
                    + "']/*[local-name()='" + elemName + "']";
        }
        String guess = guessElementName(typeName);
        return "//*[local-name()='" + guess + "']/*[local-name()='" + elemName + "']";
    }

    /** Lower-cases the first character of a type name (strips common suffixes). */
    private static String guessElementName(String typeName) {
        String n = typeName;
        for (String suffix : new String[]{"_t", "Type", "_Type", "T"}) {
            if (n.endsWith(suffix)) { n = n.substring(0, n.length() - suffix.length()); break; }
        }
        if (n.isEmpty()) return typeName;
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }

    // =========================================================================
    // Output
    // =========================================================================

    static void writeCandidateDelta(List<Candidate> candidates,
                                    String fromVer, String toVer, String view,
                                    Path output) throws Exception {
        writeCandidateDelta(candidates, fromVer, toVer, view, null, null, output);
    }

    static void writeCandidateDelta(List<Candidate> candidates,
                                    String fromVer, String toVer, String view,
                                    SchemaModel fromModel, SchemaModel toModel,
                                    Path output) throws Exception {
        DocumentBuilder db = newBuilder();
        Document doc = db.newDocument();

        Element root = doc.createElementNS(CD_NS, "candidate-delta");
        root.setAttribute("from", fromVer);
        root.setAttribute("to",   toVer);
        root.setAttribute("view", view);
        root.setAttribute("generated-at", Instant.now().toString());
        root.setAttribute("xmlns", CD_NS);

        // Embed namespace and schema-dir metadata for reviewer tooling
        if (fromModel != null) {
            if (fromModel.targetNamespace != null)
                root.setAttribute("from-namespace", fromModel.targetNamespace);
            if (fromModel.schemaDir != null)
                root.setAttribute("from-schema-dir", fromModel.schemaDir.toString());
        }
        if (toModel != null) {
            if (toModel.targetNamespace != null)
                root.setAttribute("to-namespace", toModel.targetNamespace);
            if (toModel.schemaDir != null)
                root.setAttribute("to-schema-dir", toModel.schemaDir.toString());
        }
        doc.appendChild(root);

        root.appendChild(doc.createComment(
            "\n  Status values: PENDING (awaiting review), APPROVED, SKIPPED.\n"
          + "  Review with DeltaProfileReviewer to generate a conversion-delta.xml profile.\n"
          + "  DROPPED_* entries are informational only — they need no default.\n"
          + "  RENAMED_* entries require human confirmation of the old→new name mapping.\n"));

        int id = 1;
        for (Candidate c : candidates) {
            Element el = doc.createElementNS(CD_NS, "candidate");
            el.setAttribute("id",                String.valueOf(id++));
            el.setAttribute("change-type",       c.changeType.name());
            el.setAttribute("type-name",         c.typeName);
            el.setAttribute("node-name",         c.nodeName);
            el.setAttribute("target-constraint", c.targetConstraint);
            if (c.schemaDefault != null)
                el.setAttribute("schema-default", c.schemaDefault);
            el.setAttribute("suggested-xpath",  c.suggestedXpath);
            el.setAttribute("constraint-type",  c.constraintType);
            if (c.suggestedName != null)
                el.setAttribute("suggested-name", c.suggestedName);
            el.setAttribute("status",           "PENDING");
            el.setAttribute("reviewer-value",   "");
            el.setAttribute("reviewer-scope",   "");
            el.setAttribute("reviewer-note",    "");

            // Attach the XSD file that defines this type (relative to from-schema-dir)
            SchemaModel definingModel = (c.changeType == Candidate.ChangeType.DROPPED_ATTRIBUTE
                    || c.changeType == Candidate.ChangeType.DROPPED_ELEMENT
                    || c.changeType == Candidate.ChangeType.RENAMED_ATTRIBUTE
                    || c.changeType == Candidate.ChangeType.RENAMED_ELEMENT)
                    ? fromModel : toModel;
            if (definingModel != null && definingModel.typeToFile.containsKey(c.typeName)) {
                Path typeFile = definingModel.typeToFile.get(c.typeName);
                Path baseDir  = definingModel.schemaDir;
                String rel = (baseDir != null)
                        ? baseDir.relativize(typeFile).toString().replace('\\', '/')
                        : typeFile.toString();
                el.setAttribute("source-xsd", rel);
            }

            root.appendChild(el);
        }

        // Write
        Files.createDirectories(output.getParent() != null
                ? output.getParent() : Paths.get("."));
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try (FileOutputStream fos = new FileOutputStream(output.toFile())) {
            tf.transform(new DOMSource(doc), new StreamResult(fos));
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static Path findMainSchema(Path dir) throws IOException {
        // Look for fpml-main-*.xsd
        try (var ds = Files.newDirectoryStream(dir, "fpml-main-*.xsd")) {
            for (Path p : ds) return p;
        }
        return null;
    }

    private static DocumentBuilder newBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(new ErrorHandler() {
            public void warning(SAXParseException e)  { LOG.fine(e.getMessage()); }
            public void error(SAXParseException e)    { LOG.fine(e.getMessage()); }
            public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        return db;
    }

    private static boolean isXsdElement(Node n, String localName) {
        if (n.getNodeType() != Node.ELEMENT_NODE) return false;
        Element el = (Element) n;
        return localName.equals(el.getLocalName()) && isXsdNs(el);
    }

    private static boolean isXsdNs(Element el) {
        String ns = el.getNamespaceURI();
        return XSD_NS.equals(ns) || ns == null; // namespace-unaware parse fallback
    }

    private static String localName(Element el) {
        String ln = el.getLocalName();
        return ln != null ? ln : el.getNodeName();
    }

    /** Strips namespace prefix from a QName. */
    private static String unqualify(String qname) {
        if (qname == null) return "";
        int colon = qname.indexOf(':');
        return colon >= 0 ? qname.substring(colon + 1) : qname;
    }

    private static Element firstChildXsd(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (isXsdElement(n, localName)) return (Element) n;
        }
        return null;
    }

    private static int safeInt(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}

