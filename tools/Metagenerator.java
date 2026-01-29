import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.w3c.dom.ls.*;

/**
 * Simple prototype metagenerator utility (Java).
 *
 * Usage (from repo root):
 *   javac tools\Metagenerator.java
 *   java -cp tools Metagenerator --meta files-fpml/meta/fpml-5-13-confirmation.generated.xml --data-dir files-fpml/data --report tools/metagenerator-report-5-13.json [--update-meta] [--backup]
 *
 * Additional usage to generate a meta file from a schema set:
 *   java -cp tools Metagenerator --schema-dir files-fpml/schemas/fpml5-13/confirmation --generate-meta files-fpml/meta/fpml-5-13-confirmation.generated.xml --data-dir files-fpml/data
 *
 * This reproduces the earlier Python prototype but implemented in Java with no external
 * dependencies so it can be compiled with the JDK.
 */
public class Metagenerator {

    private static final String NS_Fpml = "urn:HandCoded:FpML-Releases";
    private static final String NS_META = "urn:HandCoded:Releases";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseArgs(args);
        // New: merge-schemes mode: combine many small scheme XML files into one schemes file
        if (flags.containsKey("merge-schemes-dir") && flags.containsKey("out-schemes")) {
            Path schemesDir = Paths.get(flags.get("merge-schemes-dir"));
            Path out = Paths.get(flags.get("out-schemes"));
            boolean overwrite = flags.containsKey("overwrite");
            mergeSchemesDir(schemesDir, out, overwrite);
            return;
        }
        // New: convert genericode codelist files into fpml <schemeDefinitions> and write a single schemes file
        // Extended CLI options:
        // --convert-codelist-dir <dir> --out-schemes <file> [--overwrite] [--recursive] [--set-of-schemes <file>] [--prefer-canonical-version]
        if (flags.containsKey("convert-codelist-dir") && flags.containsKey("out-schemes")) {
            Path codelistDir = Paths.get(flags.get("convert-codelist-dir"));
            Path out = Paths.get(flags.get("out-schemes"));
            boolean overwrite = flags.containsKey("overwrite");
            boolean recursive = flags.containsKey("recursive");
            Path setOfSchemes = flags.containsKey("set-of-schemes") ? Paths.get(flags.get("set-of-schemes")) : null;
            boolean preferCanonical = flags.containsKey("prefer-canonical-version");
            convertGenericodeDir(codelistDir, out, overwrite, recursive, setOfSchemes, preferCanonical);
            return;
        }
        // basic validation
        if (!flags.containsKey("meta") && !(flags.containsKey("schema-dir") && flags.containsKey("generate-meta"))) {
            System.err.println("Usage: java Metagenerator --meta <meta-file> --data-dir <files-fpml/data> --report <report.json> [--update-meta] [--backup]");
            System.err.println("Or: java Metagenerator --schema-dir <schema-dir> --generate-meta <out-meta.xml> --data-dir <files-fpml/data>");
            System.err.println("Or: java Metagenerator --merge-schemes-dir <dir> --out-schemes <file> [--overwrite]");
            System.exit(2);
        }

        // If report/meta-analysis mode
        if (flags.containsKey("meta")) {
            Path meta = Paths.get(flags.get("meta"));
            Path dataDir = Paths.get(flags.getOrDefault("data-dir", "files-fpml/data"));
            Path report = Paths.get(flags.getOrDefault("report", "tools/metagenerator-report.json"));
            boolean update = flags.containsKey("update-meta");
            boolean backup = flags.containsKey("backup");

            if (!Files.isDirectory(dataDir)) {
                System.err.println("Error: data-dir not found: " + dataDir);
                System.exit(2);
            }
            if (!Files.isRegularFile(meta)) {
                System.err.println("Error: meta file not found: " + meta);
                System.exit(2);
            }

            Map<String, List<String>> canonicalMap = buildCanonicalMap(dataDir);
            List<String> schemeUris = parseMetaSchemeUris(meta);

            Map<String,Object> reportObj = new LinkedHashMap<>();
            reportObj.put("metaFile", meta.toString());
            reportObj.put("dataDir", dataDir.toString());

            Map<String, List<String>> foundSchemeFiles = new TreeMap<>();
            for (Map.Entry<String,List<String>> e : canonicalMap.entrySet()) {
                foundSchemeFiles.put(e.getKey(), e.getValue());
            }
            reportObj.put("foundSchemeFiles", foundSchemeFiles);

            Map<String, List<String>> matched = new TreeMap<>();
            List<String> unmatched = new ArrayList<>();
            Set<String> filesThatMatch = new TreeSet<>();

            for (String su : new TreeSet<>(new HashSet<>(schemeUris))) {
                if (canonicalMap.containsKey(su)) {
                    List<String> v = canonicalMap.get(su);
                    matched.put(su, v);
                    filesThatMatch.addAll(v);
                } else {
                    String heuristic = heuristicStrip(su);
                    if (heuristic != null && canonicalMap.containsKey(heuristic)) {
                        List<String> v = canonicalMap.get(heuristic);
                        matched.put(su, v);
                        filesThatMatch.addAll(v);
                    } else {
                        unmatched.add(su);
                    }
                }
            }

            reportObj.put("matched", matched);
            reportObj.put("unmatched", unmatched);

            List<String> added = new ArrayList<>();
            if (update) {
                // Always attempt to (re)generate schemeDefault entries based on fpml-schemes whitelist
                try {
                    ensureSchemeDefaultsInMeta(meta, dataDir, backup);
                } catch (Exception ex) {
                    System.err.println("Warning: failed to update meta with scheme defaults: " + ex.getMessage());
                }
                if (!filesThatMatch.isEmpty()) {
                    try {
                        // also attempt to add any missing fpml:schemes references (no-op currently)
                        added = ensureFpmlSchemesInMeta(meta, filesThatMatch, backup);
                    } catch (Exception ex) {
                        System.err.println("Warning: failed to ensure fpml:schemes in meta: " + ex.getMessage());
                    }
                }
            }
            reportObj.put("addedSchemesEntries", added);

            // write JSON report
            try (BufferedWriter w = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
                writeJson(reportObj, w, 0);
            }

            System.out.println("Report written to " + report.toString());
            System.out.println("Matched: " + matched.size() + " Unmatched: " + unmatched.size());
            if (!added.isEmpty()) {
                System.out.println("Added <fpml:schemes> entries: " + added);
            }
            return;
        }

        // If generate-meta mode
        if (flags.containsKey("schema-dir") && flags.containsKey("generate-meta")) {
            Path schemaDir = Paths.get(flags.get("schema-dir"));
            Path outMeta = Paths.get(flags.get("generate-meta"));
            Path dataDir = Paths.get(flags.getOrDefault("data-dir", "files-fpml/data"));
            if (!Files.isDirectory(schemaDir)) {
                System.err.println("Error: schema-dir not found: " + schemaDir);
                System.exit(2);
            }
            SchemaAnalysis result = analyzeSchemaDir(schemaDir);
            Document metaDoc = buildMetaDocument(result, dataDir);
            // write to outMeta
            if (Files.exists(outMeta)) {
                // only overwrite if name ends with .generated.xml or user specified a generated filename
                if (!outMeta.getFileName().toString().endsWith(".generated.xml")) {
                    // If the target meta exists and is not a .generated.xml, update it in-place to add scheme defaults/schemes
                    System.out.println("Meta file exists: updating in-place: " + outMeta);
                    // create backup
                    try {
                        ensureSchemeDefaultsInMeta(outMeta, dataDir, true);
                        System.out.println("Updated meta file: " + outMeta);
                    } catch (Exception ex) {
                        System.err.println("Failed to update existing meta file: " + ex.getMessage());
                        System.exit(3);
                    }
                    System.exit(0);
                }
            } else {
                // ensure parent dirs
                if (outMeta.getParent() != null) Files.createDirectories(outMeta.getParent());
            }
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            // remove empty text nodes to avoid excessive blank lines
            pruneNonFpmlSchemeDefaults(metaDoc);
            removeEmptyTextNodes(metaDoc);
            try (OutputStream os = Files.newOutputStream(outMeta, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                t.transform(new DOMSource(metaDoc), new StreamResult(os));
            }
            // Post-process the generated meta to ensure scheme defaults/dsig import/fpml:schemes are present
            try {
                ensureSchemeDefaultsInMeta(outMeta, dataDir, false);
            } catch (Exception ex) {
                System.err.println("Warning: post-processing generated meta failed: " + ex.getMessage());
            }
         System.out.println("Generated meta written to " + outMeta);
         return;
     }
 }

    // New: analyze schema directory to pick main schema and collect root elements and imports
    private static class SchemaAnalysis {
        String targetNamespace;
        String mainSchemaFileName;
        List<String> rootElements = new ArrayList<>();
        Set<String> importNamespaces = new TreeSet<>();
    }

    private static SchemaAnalysis analyzeSchemaDir(Path schemaDir) throws Exception {
        SchemaAnalysis out = new SchemaAnalysis();
        List<Path> xsds = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(schemaDir, "**/*.xsd")) {
            // DirectoryStream doesn't support glob recursively; instead walk
        } catch (Exception e) {
            // ignore
        }
        // collect recursively
        Files.walk(schemaDir).forEach(p -> { if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".xsd")) xsds.add(p); });
        if (xsds.isEmpty()) throw new IllegalArgumentException("No .xsd files found in " + schemaDir);

        // heuristic: pick file with 'main' in name if exists; otherwise file with most top-level global elements
        Path best = null; int bestCount = -1;
        Map<Path, Integer> topCount = new HashMap<>();
        for (Path p : xsds) {
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                // Prevent parser from writing errors to stderr by providing a noop ErrorHandler
                db.setErrorHandler(new org.xml.sax.ErrorHandler() {
                    public void warning(org.xml.sax.SAXParseException e) { /* ignore */ }
                    public void error(org.xml.sax.SAXParseException e) { /* ignore */ }
                    public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                });
                Document doc = db.parse(p.toFile());
                Element schema = doc.getDocumentElement();
                String tns = schema.getAttribute("targetNamespace");
                if (out.targetNamespace == null && tns != null && !tns.isEmpty()) out.targetNamespace = tns;
                // count direct child xs:element under schema
                NodeList children = schema.getChildNodes();
                int count = 0;
                for (int i=0;i<children.getLength();i++) {
                    Node n = children.item(i);
                    if (n instanceof Element) {
                        Element e = (Element)n;
                        String local = e.getLocalName();
                        if ("element".equals(local) && (XSD_NS.equals(e.getNamespaceURI()) || e.getNamespaceURI()==null)) {
                            String nm = e.getAttribute("name");
                            if (nm != null && !nm.isEmpty()) {
                                out.rootElements.add(nm);
                                count++;
                            }
                        }
                    }
                }
                topCount.put(p, count);
                if (p.getFileName().toString().toLowerCase().contains("main")) {
                    best = p;
                    bestCount = count;
                }
                // collect imports
                NodeList imports = schema.getElementsByTagNameNS(XSD_NS, "import");
                for (int i=0;i<imports.getLength();i++) {
                    Element imp = (Element) imports.item(i);
                    String ns = imp.getAttribute("namespace");
                    if (ns != null && !ns.isEmpty()) out.importNamespaces.add(ns);
                }
            } catch (Exception ex) {
                // ignore parse errors for individual files
            }
        }
        if (best == null) {
            // pick file with highest count
            for (Map.Entry<Path,Integer> e : topCount.entrySet()) {
                if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
            }
        }
        if (best != null) out.mainSchemaFileName = best.getFileName().toString();
        // dedupe rootElements
        Set<String> dedup = new TreeSet<>(out.rootElements);
        out.rootElements = new ArrayList<>(dedup);
        return out;
    }

    private static Document buildMetaDocument(SchemaAnalysis sa, Path dataDir) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.newDocument();
        // create root with correct namespaces
        Element root = doc.createElementNS(NS_META, "schemaRelease");
        root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:fpml", NS_Fpml);
        root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
        root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns", NS_META);
        root.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:type", "fpml:SchemaRelease");
        doc.appendChild(root);

        // classLoader entries (match repository convention)
        Element cl1 = doc.createElement("classLoader");
        cl1.setAttribute("class", "com.handcoded.fpml.meta.FpMLSchemaReleaseLoader");
        cl1.setAttribute("platform", "Java");
        root.appendChild(cl1);
        Element cl2 = doc.createElement("classLoader");
        cl2.setAttribute("class", "HandCoded.FpML.Meta.FpMLSchemaReleaseLoader");
        cl2.setAttribute("platform", ".Net");
        root.appendChild(cl2);

        // version: try to extract from mainSchemaFileName (e.g., fpml-main-5-13.xsd)
        String version = "";
        if (sa.mainSchemaFileName != null) {
            String fn = sa.mainSchemaFileName;
            // find pattern like -5-13 or 5-13
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+-\\d+)").matcher(fn);
            if (m.find()) version = m.group(1);
        }
        Element ver = doc.createElement("version");
        ver.setTextContent(version.isEmpty() ? "" : version);
        root.appendChild(ver);

        if (sa.targetNamespace != null) {
            Element ns = doc.createElement("namespaceUri");
            ns.setTextContent(sa.targetNamespace);
            root.appendChild(ns);
        }
        if (sa.mainSchemaFileName != null) {
            Element sl = doc.createElement("schemaLocation");
            sl.setTextContent(sa.mainSchemaFileName);
            root.appendChild(sl);
        }
        Element pref = doc.createElement("preferredPrefix");
        pref.setTextContent("fpml");
        root.appendChild(pref);

        // add rootElement entries
        for (String re : sa.rootElements) {
            Element reEl = doc.createElement("rootElement");
            reEl.setTextContent(re);
            root.appendChild(reEl);
        }

        // add import for dsig if detected among import namespaces
        if (sa.importNamespaces.contains(DSIG_NS)) {
            Element imp = doc.createElement("import");
            imp.setTextContent("files-fpml/meta/dsig-1-0.xml");
            root.appendChild(imp);
        }

        // Add schemeDefault entries (and corresponding defaultAttribute mappings) based on files in dataDir
        // Only add if none exist already in the meta template
        NodeList existingDefaults = doc.getElementsByTagNameNS(NS_Fpml, "schemeDefault");
        if (existingDefaults.getLength() == 0) {
            Map<String,String> whitelist = null;
            try {
                whitelist = loadFpmlSchemesWhitelist();
                List<String> keys = new ArrayList<>(whitelist.keySet());
                Collections.sort(keys);
                for (String attrName : keys) {
                    String cu = whitelist.get(attrName);
                    if (cu == null || cu.trim().isEmpty()) continue;
                    try {
                        java.net.URI u = new java.net.URI(cu);
                        String host = u.getHost();
                        String path = u.getPath();
                        if (host == null || !host.toLowerCase().contains("fpml.org")) continue;
                        if (path == null || !path.toLowerCase().contains("/coding-scheme/")) continue;
                    } catch (Exception ex) { continue; }
                    Element sd = doc.createElementNS(NS_Fpml, "fpml:schemeDefault");
                    Element a = doc.createElementNS(NS_Fpml, "fpml:attribute"); a.setTextContent(attrName);
                    Element su = doc.createElementNS(NS_Fpml, "fpml:schemeUri"); su.setTextContent(cu);
                    sd.appendChild(a); sd.appendChild(su); root.appendChild(sd);
                }
            } catch (Exception ex) {
                // don't fail meta generation for scheme defaults; print warning
                System.err.println("Warning: failed to build scheme defaults: " + ex.getMessage());
            }
        }

        // prefer matching schemes file for version if present, else include additionalDefinitions
        String schemesFile = null;
        if (!version.isEmpty()) {
            Path candidate = dataDir.resolve("schemes" + version + ".xml");
            if (Files.exists(candidate)) schemesFile = dataDir.resolve(candidate.getFileName()).toString().replace('\\','/');
        }
        if (schemesFile == null) {
            Path add = dataDir.resolve("additionalDefinitions.xml");
            if (Files.exists(add)) schemesFile = dataDir.resolve(add.getFileName()).toString().replace('\\','/');
        }
        if (schemesFile != null) {
            Element s = doc.createElementNS(NS_Fpml, "fpml:schemes");
            s.setTextContent(schemesFile);
            root.appendChild(s);
        }

        // Remove any fpml:schemeDefault entries whose fpml:schemeUri is not an fpml.org coding-scheme
        pruneNonFpmlSchemeDefaults(doc);

        return doc;
    }

    // Remove any fpml:schemeDefault entries where the schemeUri does not point to an fpml.org coding-scheme
    private static void pruneNonFpmlSchemeDefaults(Document doc) {
        NodeList defs = doc.getElementsByTagNameNS(NS_Fpml, "schemeDefault");
        List<Node> toRemove = new ArrayList<>();
        for (int i=0;i<defs.getLength();i++) {
            Element sd = (Element) defs.item(i);
            NodeList sus = sd.getElementsByTagNameNS(NS_Fpml, "schemeUri");
            String uriText = null;
            if (sus.getLength() > 0) uriText = sus.item(0).getTextContent().trim();
            if (uriText == null || uriText.isEmpty()) {
                toRemove.add(sd);
                continue;
            }
            try {
                java.net.URI u = new java.net.URI(uriText);
                String host = u.getHost();
                String path = u.getPath();
                if (host == null || !host.toLowerCase().contains("fpml.org") || path == null || !path.toLowerCase().contains("/coding-scheme/")) {
                    toRemove.add(sd);
                }
            } catch (Exception ex) {
                toRemove.add(sd);
            }
        }
        for (Node n : toRemove) n.getParentNode().removeChild(n);
    }

    private static Map<String, String> parseArgsToMap(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i=0;i<args.length;i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (key.equals("update-meta") || key.equals("backup")) {
                    m.put(key, "true");
                } else {
                    if (i+1 < args.length) {
                        m.put(key, args[i+1]);
                        i++;
                    }
                }
            }
        }
        return m;
    }

    private static Map<String,String> parseArgs(String[] args) {
        return parseArgsToMap(args);
    }

    // Parse a schemes XML (files-fpml/data/*.xml) and return a set of URIs found in scheme elements.
    private static Set<String> parseSchemesFile(Path p) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(p)) return out;
        try {
            byte[] bytes = Files.readAllBytes(p);
            String text = new String(bytes, StandardCharsets.UTF_8);
            // strip BOM if present
            if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            // suppress parser error output
            db.setErrorHandler(new org.xml.sax.ErrorHandler() {
                public void warning(org.xml.sax.SAXParseException e) { /* ignore */ }
                public void error(org.xml.sax.SAXParseException e) { /* ignore */ }
                public void fatalError(org.xml.sax.SAXParseException e) { /* ignore */ }
            });
            Document doc = null;
            try {
                // parse from string to avoid prolog issues
                doc = db.parse(new org.xml.sax.InputSource(new java.io.StringReader(text)));
            } catch (Exception ex) {
                // fallback to try parsing file directly (some parsers handle encodings better)
                try { doc = db.parse(p.toFile()); } catch (Exception ex2) { doc = null; }
            }
            if (doc != null) {
                NodeList nodes = doc.getElementsByTagNameNS("*", "scheme");
                for (int i=0;i<nodes.getLength();i++) {
                    Element e = (Element) nodes.item(i);
                    String cu = e.getAttribute("canonicalUri"); if (cu!=null && !cu.isEmpty()) out.add(cu.trim());
                    String u = e.getAttribute("uri"); if (u!=null && !u.isEmpty()) out.add(u.trim());
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Exception ex) {
            // ignore and fallback to text scan
        }
        byte[] bytes = Files.readAllBytes(p);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\"'<>\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    // Parse a meta file and extract scheme URIs referenced by it. Returns list of URIs (may be empty).
    private static List<String> parseMetaSchemeUris(Path metaFile) throws Exception {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(metaFile)) return out;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(metaFile.toFile());
        Set<String> set = new LinkedHashSet<>();
        // fpml:schemes elements (namespaced)
        NodeList nsSchemes = doc.getElementsByTagNameNS(NS_Fpml, "schemes");
        for (int i=0;i<nsSchemes.getLength();i++) {
            String v = nsSchemes.item(i).getTextContent().trim(); if (!v.isEmpty()) set.addAll(Arrays.asList(v.split("\\s+")));
        }
        // non-namespaced <schemes>
        NodeList ns2 = doc.getElementsByTagName("schemes");
        for (int i=0;i<ns2.getLength();i++) { String v = ns2.item(i).getTextContent().trim(); if (!v.isEmpty()) set.addAll(Arrays.asList(v.split("\\s+"))); }
        // <scheme> elements with uri attributes
        NodeList schemes = doc.getElementsByTagNameNS("*","scheme");
        for (int i=0;i<schemes.getLength();i++) {
            Element e = (Element) schemes.item(i);
            String u = e.getAttribute("uri"); if (u!=null && !u.isEmpty()) set.add(u.trim());
            String cu = e.getAttribute("canonicalUri"); if (cu!=null && !cu.isEmpty()) set.add(cu.trim());
        }
        // Fallback: scan text for http(s) URLs
        String text = new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\"'<>\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) set.add(m.group());
        out.addAll(set);
        return out;
    }

    // Heuristic to strip a trailing version suffix like '-5-11' from a scheme URI to attempt fuzzy matching.
    private static String heuristicStrip(String uri) {
        if (uri == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(.*)-\\d+-\\d+$").matcher(uri);
        if (m.find()) return m.group(1);
        return null;
    }

    // No-op: ensure fpml:schemes entries in meta (placeholder for future implementation)
    private static List<String> ensureFpmlSchemesInMeta(Path meta, Set<String> filesThatMatch, boolean backup) throws Exception {
        return new ArrayList<>();
    }

    // Ensure schemeDefault entries exist in an existing meta file by regenerating from fpml-schemes.html
    private static void ensureSchemeDefaultsInMeta(Path meta, Path dataDir, boolean backup) throws Exception {
        if (meta == null || !Files.isRegularFile(meta)) throw new IllegalArgumentException("meta not found: " + meta);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(meta.toFile());
        Element root = doc.getDocumentElement();
        if (root == null) throw new IllegalArgumentException("meta has no root");

        if (backup) {
            Path bak = meta.resolveSibling(meta.getFileName().toString() + ".bak");
            Files.copy(meta, bak, StandardCopyOption.REPLACE_EXISTING);
        }

        // Remove existing <fpml:schemeDefault> elements
        NodeList existingDefaults = doc.getElementsByTagNameNS(NS_Fpml, "schemeDefault");
        List<Node> toRemove = new ArrayList<>();
        for (int i=0;i<existingDefaults.getLength();i++) toRemove.add(existingDefaults.item(i));
        for (Node n : toRemove) n.getParentNode().removeChild(n);

        Map<String,String> whitelist = null;
        try {
            whitelist = loadFpmlSchemesWhitelist();
            List<String> keys = new ArrayList<>(whitelist.keySet());
            Collections.sort(keys);
            for (String attrName : keys) {
                String cu = whitelist.get(attrName);
                if (cu == null || cu.trim().isEmpty()) continue;
                try {
                    java.net.URI u = new java.net.URI(cu);
                    String host = u.getHost();
                    String path = u.getPath();
                    if (host == null || !host.toLowerCase().contains("fpml.org")) continue;
                    if (path == null || !path.toLowerCase().contains("/coding-scheme/")) continue;
                } catch (Exception ex) { continue; }
                Element sd = doc.createElementNS(NS_Fpml, "fpml:schemeDefault");
                Element a = doc.createElementNS(NS_Fpml, "fpml:attribute"); a.setTextContent(attrName);
                Element su = doc.createElementNS(NS_Fpml, "fpml:schemeUri"); su.setTextContent(cu);
                sd.appendChild(a); sd.appendChild(su); root.appendChild(sd);
            }
        } catch (Exception ex) {
            System.err.println("Warning: failed to update scheme defaults: " + ex.getMessage());
        }

        // Ensure dsig import is present if the dsig meta file exists
        try {
            Path dsigMeta = Paths.get("files-fpml/meta/dsig-1-0.xml");
            boolean hasDsig = false;
            NodeList imports = doc.getElementsByTagName("import");
            for (int i=0;i<imports.getLength();i++) {
                Node im = imports.item(i);
                if (im != null && im.getTextContent() != null && im.getTextContent().trim().equals(dsigMeta.toString().replace('\\','/'))) { hasDsig = true; break; }
            }
            if (!hasDsig && Files.exists(dsigMeta)) {
                Element imp = doc.createElement("import");
                imp.setTextContent(dsigMeta.toString().replace('\\','/'));
                // append before schemes if present, otherwise at end
                NodeList schemesNodes = doc.getElementsByTagNameNS(NS_Fpml, "schemes");
                if (schemesNodes.getLength() > 0) {
                    Node sNode = schemesNodes.item(0);
                    root.insertBefore(imp, sNode);
                } else {
                    root.appendChild(imp);
                }
            }
        } catch (Exception ex) {
            System.err.println("Warning: failed to ensure dsig import: " + ex.getMessage());
        }

        // Ensure fpml:schemes element points to appropriate schemes file under dataDir
        try {
            String version = null;
            NodeList verNodes = doc.getElementsByTagName("version");
            if (verNodes.getLength() > 0) version = verNodes.item(0).getTextContent().trim();
            Path candidate = null;
            if (version != null && !version.isEmpty()) {
                Path cand = dataDir.resolve("schemes" + version + ".xml");
                if (Files.exists(cand)) candidate = cand;
            }
            if (candidate == null) {
                Path add = dataDir.resolve("additionalDefinitions.xml");
                if (Files.exists(add)) candidate = add;
            }
            if (candidate != null) {
                String rel = dataDir.resolve(candidate.getFileName()).toString().replace('\\','/');
                boolean hasSchemes = false;
                NodeList schemesNodes = doc.getElementsByTagNameNS(NS_Fpml, "schemes");
                if (schemesNodes.getLength() > 0) {
                    // update if empty or different
                    Element s = (Element) schemesNodes.item(0);
                    if (s.getTextContent() == null || s.getTextContent().trim().isEmpty() || !s.getTextContent().trim().equals(rel)) {
                        s.setTextContent(rel);
                    }
                    hasSchemes = true;
                }
                if (!hasSchemes) {
                    Element s = doc.createElementNS(NS_Fpml, "fpml:schemes");
                    s.setTextContent(rel);
                    root.appendChild(s);
                }
            }
        } catch (Exception ex) {
            System.err.println("Warning: failed to ensure fpml:schemes entry: " + ex.getMessage());
        }

        pruneNonFpmlSchemeDefaults(doc);
        removeEmptyTextNodes(doc);
        writeDocumentPrettyToFile(doc, meta);

        // diagnostics: summarize changes
        int whitelistCount = (whitelist==null)?0:whitelist.size();
        int schemeDefaultCount = doc.getElementsByTagNameNS(NS_Fpml, "schemeDefault").getLength();
        System.out.println("ensureSchemeDefaultsInMeta: fpml-schemes.html path=" + (lastFpmlSchemesHtmlPath==null?"(not found)":lastFpmlSchemesHtmlPath.toString()));
        System.out.println("ensureSchemeDefaultsInMeta: " + whitelistCount + " whitelist entries found");
        System.out.println("  " + schemeDefaultCount + " schemeDefault elements present after update");
    }

    // tiny JSON writer (recursive for maps/lists)
    private static void writeJson(Object obj, Writer w, int indent) throws IOException {
        String ind = repeat(' ', indent);
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String,Object> map = (Map<String,Object>) obj;
            w.write('{');
            boolean first = true;
            for (Map.Entry<String,Object> e : map.entrySet()) {
                if (!first) w.write(',');
                w.write('\n');
                w.write(ind + "  \"");
                w.write(escapeJson(e.getKey()));
                w.write("\": ");
                writeJson(e.getValue(), w, indent+2);
                first = false;
            }
            w.write('\n');
            w.write(ind + '}');
        } else if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;
            w.write('[');
            boolean first = true;
            for (Object it : list) {
                if (!first) w.write(',');
                w.write('\n');
                w.write(ind + "  ");
                writeJson(it, w, indent+2);
                first = false;
            }
            w.write('\n');
            w.write(ind + ']');
        } else if (obj instanceof String) {
            w.write('"' + escapeJson((String)obj) + '"');
        } else if (obj instanceof Number || obj instanceof Boolean) {
            w.write(obj.toString());
        } else if (obj == null) {
            w.write("null");
        } else {
            w.write('"' + escapeJson(obj.toString()) + '"');
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n]; Arrays.fill(a, c); return new String(a);
    }

    private static Map<String, List<String>> buildCanonicalMap(Path dataDir) throws Exception {
        Map<String, List<String>> map = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dataDir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.startsWith("schemes") && name.endsWith(".xml") || name.equals("additionalDefinitions.xml")) {
                    Set<String> uris = parseSchemesFile(p);
                    for (String u : uris) {
                        // Only index canonical URIs that look like fpml coding-scheme links to avoid external noise
                        try {
                            java.net.URI uri = new java.net.URI(u);
                            String host = uri.getHost();
                            String path = uri.getPath();
                            if (host == null || !host.toLowerCase().contains("fpml.org")) continue;
                            if (path == null || !path.toLowerCase().contains("/coding-scheme/")) continue;
                            map.computeIfAbsent(u, k -> new ArrayList<>()).add(dataDir.resolve(p.getFileName()).toString().replace('\\','/'));
                        } catch (Exception ex) {
                            // skip malformed or non-http URIs
                        }
                    }
                }
            }
        }
        return map;
    }

    // Merge many small scheme XML files from a directory into a single schemes XML file.
    // Deduplicates scheme entries by canonicalUri -> uri -> textContent (first wins).
    private static void mergeSchemesDir(Path schemesDir, Path outFile, boolean overwrite) throws Exception {
        if (!Files.isDirectory(schemesDir)) {
            System.err.println("Error: schemes dir not found: " + schemesDir);
            System.exit(2);
        }
        List<Path> inputs;
        try (java.util.stream.Stream<Path> s = Files.list(schemesDir)) {
            inputs = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xml")).sorted().collect(java.util.stream.Collectors.toList());
        }
        if (inputs.isEmpty()) {
            System.err.println("No .xml files found in " + schemesDir);
            System.exit(2);
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document outDoc = db.newDocument();
        Element root = outDoc.createElement("schemes");
        outDoc.appendChild(root);

        Set<String> seen = new LinkedHashSet<>();
        int totalFiles = 0;
        int totalSchemes = 0;
        int added = 0;

        for (Path p : inputs) {
            totalFiles++;
            try {
                Document d = db.parse(p.toFile());
                NodeList nodes = d.getElementsByTagNameNS("*", "scheme");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element sEl = (Element) nodes.item(i);
                    totalSchemes++;
                    String key = sEl.getAttribute("canonicalUri");
                    if (key == null || key.isEmpty()) key = sEl.getAttribute("canonicaluri");
                    if (key == null || key.isEmpty()) key = sEl.getAttribute("uri");
                    if (key == null || key.isEmpty()) key = sEl.getTextContent();
                    if (key == null) key = "";
                    key = key.trim();
                    if (seen.contains(key)) continue; // first wins
                    seen.add(key);
                    Node imp = outDoc.importNode(sEl, true);
                    root.appendChild(imp);
                    added++;
                }
            } catch (Exception ex) {
                System.err.println("Warning: failed to parse schemes file " + p + " : " + ex.getMessage());
            }
        }

        if (Files.exists(outFile) && !overwrite) {
            System.err.println("Refusing to overwrite existing file: " + outFile + " (use --overwrite to allow)");
            System.exit(3);
        }
        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
        // clean up whitespace text nodes so the output doesn't contain many blank lines
        pruneNonFpmlSchemeDefaults(outDoc);
        removeEmptyTextNodes(outDoc);
        try (OutputStream os = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeDocumentPretty(outDoc, os);
        }

        System.out.println("Merged " + totalFiles + " files, found " + totalSchemes + " scheme elements, added " + added + " to " + outFile);
    }

    // Convert a directory of Genericode (gcl:CodeList) files into a single files-fpml <schemeDefinitions> XML.
    private static void convertGenericodeDir(Path codelistDir, Path outFile, boolean overwrite, boolean recursive, Path setOfSchemes, boolean preferCanonical) throws Exception {
        if (!Files.isDirectory(codelistDir)) {
            System.err.println("Error: codelist dir not found: " + codelistDir);
            System.exit(2);
        }
        List<Path> inputs;
        if (recursive) {
            try (java.util.stream.Stream<Path> s = Files.walk(codelistDir)) {
                inputs = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xml")).sorted().collect(java.util.stream.Collectors.toList());
            }
        } else {
            try (java.util.stream.Stream<Path> s = Files.list(codelistDir)) {
                inputs = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xml")).sorted().collect(java.util.stream.Collectors.toList());
            }
        }
        if (inputs.isEmpty()) {
            System.err.println("No .xml files found in " + codelistDir);
            System.exit(2);
        }

        Set<String> allowedUris = null;
        if (setOfSchemes != null) {
            if (!Files.isRegularFile(setOfSchemes)) {
                System.err.println("Error: set-of-schemes file not found: " + setOfSchemes);
                System.exit(2);
            }
            allowedUris = readSetOfSchemesUris(setOfSchemes);
            System.out.println("Using set-of-schemes filter with " + allowedUris.size() + " URIs");
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document outDoc = db.newDocument();
        Element root = outDoc.createElement("schemeDefinitions");
        outDoc.appendChild(root);

        int files = 0, schemes = 0, added = 0;
        Set<String> seenUris = new LinkedHashSet<>();
        for (Path p : inputs) {
            files++;
            try {
                Document d = db.parse(p.toFile());
                NodeList idents = getElementsByLocalName(d, "Identification");
                Element ident = (idents.getLength() > 0) ? (Element) idents.item(0) : null;
                if (ident == null) {
                    System.err.println("Warning: skipping file without Identification: " + p);
                    continue;
                }

                String canonicalUri = getTextContentOfChild(ident, "CanonicalUri");
                String canonicalVersionUri = getTextContentOfChild(ident, "CanonicalVersionUri");
                String locationUri = getTextContentOfChild(ident, "LocationUri");
                String shortName = getTextContentOfChild(ident, "ShortName");
                String version = getTextContentOfChild(ident, "Version");

                // choose candidate URI according to preference
                String schemeUri = null;
                if (preferCanonical) {
                    schemeUri = (canonicalVersionUri != null && !canonicalVersionUri.isEmpty()) ? canonicalVersionUri : canonicalUri;
                    if ((schemeUri == null || schemeUri.isEmpty()) && locationUri != null) schemeUri = locationUri;
                } else {
                    schemeUri = (canonicalVersionUri != null && !canonicalVersionUri.isEmpty()) ? canonicalVersionUri : canonicalUri;
                }
                if (schemeUri == null || schemeUri.isEmpty()) schemeUri = (locationUri != null ? locationUri : (shortName != null ? shortName : p.getFileName().toString()));

                // If filtering by set-of-schemes, ensure this scheme is allowed
                if (allowedUris != null) {
                    boolean ok = false;
                    if (canonicalVersionUri != null && allowedUris.contains(canonicalVersionUri)) ok = true;
                    if (!ok && canonicalUri != null && allowedUris.contains(canonicalUri)) ok = true;
                    if (!ok && locationUri != null && allowedUris.contains(locationUri)) ok = true;
                    // also allow if the filename appears in allowed locations
                    if (!ok) {
                        String fname = p.getFileName().toString();
                        for (String a : allowedUris) {
                            if (a.endsWith(fname)) { ok = true; break; }
                        }
                    }
                    if (!ok) {
                        // skip file not in set
                        continue;
                    }
                }

                String schemeName = (shortName != null ? shortName : p.getFileName().toString().replaceAll("\\.xml$",""));
                if (version != null && !version.isEmpty()) schemeName = schemeName + "-" + version;

                // avoid duplicate scheme URIs
                String dedupeKey = (canonicalVersionUri != null && !canonicalVersionUri.isEmpty()) ? canonicalVersionUri : schemeUri;
                if (seenUris.contains(dedupeKey)) continue;
                seenUris.add(dedupeKey);

                Element schemeEl = outDoc.createElement("scheme");
                if (schemeUri != null) schemeEl.setAttribute("uri", schemeUri);
                if (canonicalUri != null) schemeEl.setAttribute("canonicalUri", canonicalUri);
                schemeEl.setAttribute("name", schemeName);

                // find rows (SimpleCodeList/Row) using local-name lookup
                NodeList rows = getElementsByLocalName(d, "Row");
                Element schemeValues = outDoc.createElement("schemeValues");
                for (int i=0;i<rows.getLength();i++) {
                    Node rn = rows.item(i);
                    if (!(rn instanceof Element)) continue;
                    Element row = (Element) rn;
                    NodeList vals = getElementsByLocalName(row, "SimpleValue");
                    String code = vals.getLength() > 0 ? vals.item(0).getTextContent().trim() : "";
                    String source = vals.getLength() > 1 ? vals.item(1).getTextContent().trim() : "FpML";
                    String desc = vals.getLength() > 2 ? vals.item(2).getTextContent().trim() : null;

                    Element sv = outDoc.createElement("schemeValue");
                    if (source != null && !source.isEmpty()) sv.setAttribute("schemeValueSource", source);
                    if (code != null && !code.isEmpty()) sv.setAttribute("name", code);
                    if (desc != null && !desc.isEmpty()) {
                        Element pEl = outDoc.createElement("paragraph");
                        pEl.setTextContent(desc);
                        sv.appendChild(pEl);
                    }
                    schemeValues.appendChild(sv);
                    schemes++;
                }
                schemeEl.appendChild(schemeValues);
                root.appendChild(schemeEl);
                added++;
            } catch (Exception ex) {
                System.err.println("Warning: failed to parse codelist file " + p + " : " + ex.getMessage());
            }
         }

         if (Files.exists(outFile) && !overwrite) {
             System.err.println("Refusing to overwrite existing file: " + outFile + " (use --overwrite to allow)");
             System.exit(3);
         }
         if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
         try (OutputStream os = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
             writeDocumentPretty(outDoc, os);
         }

         System.out.println("Converted " + files + " files to " + added + " scheme entries (" + schemes + " rows) into " + outFile);
     }

     private static Set<String> readSetOfSchemesUris(Path setFile) throws Exception {
         Set<String> out = new LinkedHashSet<>();
         DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
         dbf.setNamespaceAware(true);
         DocumentBuilder db = dbf.newDocumentBuilder();
         Document doc = db.parse(setFile.toFile());
        // gather CanonicalVersionUri, CanonicalUri, LocationUri (namespace-agnostic)
        NodeList locs = getElementsByLocalName(doc, "LocationUri"); for (int i=0;i<locs.getLength();i++) { String s = locs.item(i).getTextContent().trim(); if (!s.isEmpty()) out.add(s); }
        NodeList cvrs = getElementsByLocalName(doc, "CanonicalVersionUri"); for (int i=0;i<cvrs.getLength();i++) { String s = cvrs.item(i).getTextContent().trim(); if (!s.isEmpty()) out.add(s); }
        NodeList crs = getElementsByLocalName(doc, "CanonicalUri"); for (int i=0;i<crs.getLength();i++) { String s = crs.item(i).getTextContent().trim(); if (!s.isEmpty()) out.add(s); }
        // also try bare element names (already handled by getElementsByLocalName)
         return out;
     }

    private static String getTextContentOfChild(Element parent, String childName) {
        NodeList nl = getElementsByLocalName(parent, childName);
        if (nl != null && nl.getLength() > 0) return nl.item(0).getTextContent().trim();
        return null;
    }

    // Robust helper: locate elements by local name preferring the genericode-like namespace, then no-namespace, then any namespace.
    private static NodeList getElementsByLocalName(Node node, String localName) {
        if (node == null) {
            return new org.w3c.dom.NodeList() { public Node item(int i){return null;} public int getLength(){return 0;} };
        }
        if (node instanceof Document) {
            Document doc = (Document) node;
            NodeList nl = doc.getElementsByTagNameNS("*", localName);
            if (nl.getLength() > 0) return nl;
            // fallback empty
            return nl;
        } else if (node instanceof Element) {
            Element el = (Element) node;
            NodeList nl = el.getElementsByTagNameNS("*", localName);
            if (nl.getLength() > 0) return nl;
            return nl;
        } else {
            Document doc = node.getOwnerDocument();
            if (doc != null) return doc.getElementsByTagNameNS("*", localName);
            return new org.w3c.dom.NodeList() { public Node item(int i){return null;} public int getLength(){return 0;} };
        }
    }

    // Pretty-print a DOM Document to an OutputStream without relying on vendor-specific properties.
    private static void writeDocumentPretty(Document doc, OutputStream os) throws Exception {
        if (doc == null) throw new IllegalArgumentException("doc null");
        // Try DOM L3 LS pretty-print first (no xml.apache string required)
        try {
            DOMImplementation impl = doc.getImplementation();
            if (impl != null && impl.hasFeature("LS", "3.0")) {
                DOMImplementationLS implLS = (DOMImplementationLS) impl.getFeature("LS", "3.0");
                LSSerializer serializer = implLS.createLSSerializer();
                DOMConfiguration cfg = serializer.getDomConfig();
                if (cfg.canSetParameter("format-pretty-print", Boolean.TRUE)) {
                    cfg.setParameter("format-pretty-print", Boolean.TRUE);
                }
                LSOutput out = implLS.createLSOutput();
                out.setEncoding("UTF-8");
                out.setByteStream(os);
                serializer.write(doc, out);
                return;
            }
        } catch (Throwable t) {
            // ignore and fall through to Transformer fallback
        }

        // Fallback: Transformer with standard OutputKeys.INDENT (no vendor-specific indent-amount)
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.transform(new DOMSource(doc), new StreamResult(os));
    }

    // Public helper so other Java code can invoke the conversion programmatically.
    // Example: Metagenerator.runConvertCodelist("tools/codelist.../codelist", "files-fpml/data/schemes5-13.xml", true, true, null, false);
    public static void runConvertCodelist(String codelistDir, String outSchemesFile, boolean overwrite, boolean recursive, String setOfSchemesFile, boolean preferCanonical) throws Exception {
        Path dir = Paths.get(codelistDir);
        Path out = Paths.get(outSchemesFile);
        Path setOfSchemes = (setOfSchemesFile == null || setOfSchemesFile.isEmpty()) ? null : Paths.get(setOfSchemesFile);
        convertGenericodeDir(dir, out, overwrite, recursive, setOfSchemes, preferCanonical);
    }

    // Public wrapper so other Java code can update an existing meta file in-place.
    public static void updateMetaWithSchemes(String metaPath, String dataDir, boolean backup) throws Exception {
        Path m = Paths.get(metaPath);
        Path d = Paths.get(dataDir);
        ensureSchemeDefaultsInMeta(m, d, backup);
    }

    private static Path lastFpmlSchemesHtmlPath = null;

    // Find fpml-schemes.html anywhere under tools/ and return first match or null
    private static Path findFpmlSchemesHtml() throws IOException {
        // Prefer the attached expected path (stable location for the enhanced metadata snapshot).
        Path p1 = Paths.get("tools", "codelist_enhanced_metadata_2_23", "codelist_enhanced_metadata_2_23", "coding-scheme_enhanced_metadata", "fpml-schemes.html");
        if (Files.isRegularFile(p1)) { lastFpmlSchemesHtmlPath = p1; return p1; }
        // try the non-enhanced coding-scheme path as well
        Path p2 = Paths.get("tools", "codelist_enhanced_metadata_2_23", "codelist_enhanced_metadata_2_23", "coding-scheme", "fpml-schemes.html");
        if (Files.isRegularFile(p2)) { lastFpmlSchemesHtmlPath = p2; return p2; }
        // Also try a shallower path that was observed in some checkouts
        Path p3 = Paths.get("tools", "codelist_enhanced_metadata_2_23", "coding-scheme", "fpml-schemes.html");
        if (Files.isRegularFile(p3)) { lastFpmlSchemesHtmlPath = p3; return p3; }
        Path p4 = Paths.get("tools", "codelist_enhanced_metadata_2_23", "coding-scheme_enhanced_metadata", "fpml-schemes.html");
        if (Files.isRegularFile(p4)) { lastFpmlSchemesHtmlPath = p4; return p4; }
        // As a last resort, scan tools/ for the first matching file (fast enough for local repo)
        Path toolsDir = Paths.get("tools");
        if (Files.isDirectory(toolsDir)) {
            try (java.util.stream.Stream<Path> s = Files.walk(toolsDir)) {
                Optional<Path> any = s.filter(p -> p.getFileName().toString().equalsIgnoreCase("fpml-schemes.html")).findFirst();
                if (any.isPresent()) { lastFpmlSchemesHtmlPath = any.get(); return any.get(); }
            } catch (IOException ex) {
                // ignore
            }
        }
        // If the exact attached path is not present, return null
        lastFpmlSchemesHtmlPath = null;
        return null;
    }

    // Load whitelist of canonical URIs from fpml-schemes.html if present. Returns empty map if not found.
    private static Map<String,String> loadFpmlSchemesWhitelist() throws IOException {
        Path html = findFpmlSchemesHtml();
        if (html == null) return Collections.emptyMap();
        String text = new String(Files.readAllBytes(html), StandardCharsets.UTF_8);
        // Find all occurrences of fpml coding-scheme URLs and derive canonical base URIs and attribute names
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://www\\.fpml\\.org/coding-scheme/([a-z0-9\\-]+)(?:\\.xml|[^a-z0-9\\-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(text);
        Map<String,String> out = new LinkedHashMap<>();
        while (m.find()) {
            String raw = m.group(1).toLowerCase();
            // strip trailing version chunks like -1-0 or -5-11 etc
            String base = raw.replaceAll("(-\\d+)+$", "");
            if (base == null || base.isEmpty()) continue;
            String canonical = "http://www.fpml.org/coding-scheme/" + base;
            // derive attribute name: kebab-case to camelCase then append 'Scheme'
            String[] parts = base.split("[-_]");
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<parts.length;i++) {
                String part = parts[i];
                if (part.isEmpty()) continue;
                if (i==0) sb.append(part);
                else sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            String attr = sb.toString() + "Scheme";
            // avoid overwriting an existing mapping
            if (!out.containsKey(attr)) out.put(attr, canonical);
        }
        return out;
    }

    // Remove text nodes that are purely whitespace to reduce blank lines in serialized XML
    private static void removeEmptyTextNodes(Node node) {
        NodeList kids = node.getChildNodes();
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            Node c = kids.item(i);
            if (c.getNodeType() == Node.TEXT_NODE) {
                if (c.getTextContent().trim().isEmpty()) node.removeChild(c);
            } else {
                removeEmptyTextNodes(c);
            }
        }
    }

    // Helper to serialize a Document to a file path using writeDocumentPretty
    private static void writeDocumentPrettyToFile(Document doc, Path out) throws Exception {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeDocumentPretty(doc, os);
        }
    }

}
