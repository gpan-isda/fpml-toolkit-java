package com.handcoded.meta.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;

/**
 * Interactive CLI tool that walks a {@code candidate-delta.xml} file
 * (produced by {@link XsdSchemaDiffer}) and lets a human reviewer approve,
 * reject or assign default values to each candidate change.
 *
 * <p>Approved entries are written into a {@code conversion-delta.xml} profile
 * file that is ready for use by {@link com.handcoded.fpml.FpMLConversionPipeline}.</p>
 *
 * <h3>Review session</h3>
 * <p>For every {@code PENDING} candidate the reviewer is shown:</p>
 * <ul>
 *   <li>The change type (NEW_ATTRIBUTE, REQUIRED_ELEMENT, etc.).</li>
 *   <li>The XSD complex type name and node (attribute/element) name.</li>
 *   <li>The XSD constraint (required/optional, minOccurs).</li>
 *   <li>Any XSD-declared default value.</li>
 *   <li>The suggested XPath (can be overridden).</li>
 * </ul>
 *
 * <p>The reviewer responds with one of:</p>
 * <ul>
 *   <li>{@code skip} — mark as SKIPPED; no entry written to the profile.</li>
 *   <li>{@code <value>} — approve with the given default value.</li>
 * </ul>
 *
 * <p>After providing a value the reviewer is asked for an optional scope
 * (view and/or product-type) and an optional free-text note.</p>
 *
 * <p>DROPPED_* entries are shown for information but are always marked SKIPPED
 * automatically.</p>
 *
 * <h3>Flags</h3>
 * <ul>
 *   <li>{@code --skip-optional} — automatically marks all {@code schema-optional}
 *       entries as SKIPPED without prompting.</li>
 *   <li>{@code --auto-schema-defaults} — automatically approves all
 *       {@code schema-required} entries that carry a {@code schema-default}
 *       attribute, using that value as the default.</li>
 * </ul>
 *
 * <p>Using both flags together reduces a 100-entry session to only the
 * {@code schema-required} entries that have <em>no</em> XSD-declared default —
 * i.e., the ones that genuinely need a human decision.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   java com.handcoded.meta.tools.DeltaProfileReviewer \
 *        [--skip-optional] [--auto-schema-defaults] \
 *        files-fpml/conversion-profiles/candidate-5-12-to-5-13-confirmation.xml \
 *        files-fpml/conversion-profiles/confirmation-5-12-to-5-13.xml
 * </pre>
 *
 * <p>If the output path argument is omitted a default path is derived from the
 * input file name by replacing the {@code candidate-} prefix with the
 * {@code from-view} attribute value.</p>
 *
 * <p>Re-running the tool on an already-reviewed file will only prompt for
 * entries still in {@code PENDING} status, allowing a review session to be
 * resumed.</p>
 *
 * @author ISDA FpML Team
 * @since  TFP 1.x
 */
public final class DeltaProfileReviewer {

    private static final String CD_NS  =
            "http://www.handcoded.com/fpml/candidate-delta";
    private static final String DELTA_NS =
            "http://www.handcoded.com/fpml/conversion-delta";

    /** All attribute names that this reviewer explicitly processes on a <candidate> element. */
    private static final Set<String> KNOWN_ATTRS = new HashSet<>(Arrays.asList(
            "id", "change-type", "type-name", "node-name", "target-constraint",
            "schema-default", "suggested-xpath", "constraint-type", "status",
            "suggested-name", "source-xsd",
            "reviewer-value", "reviewer-scope", "reviewer-note", "reviewer-product",
            "reviewer-xpath", "reviewer-only-if-absent", "reviewer-prompt-at-runtime",
            "reviewer-rename-to"
    ));

    // =========================================================================
    // main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // ── Parse flags ──────────────────────────────────────────────────────
        boolean skipOptional       = false;
        boolean autoSchemaDefaults = false;
        List<String> positional    = new ArrayList<>();

        // Force UTF-8 output so arrow characters render correctly on Windows
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));

        for (String arg : args) {
            switch (arg) {
                case "--skip-optional":        skipOptional       = true; break;
                case "--auto-schema-defaults": autoSchemaDefaults = true; break;
                default: positional.add(arg);
            }
        }

        if (positional.isEmpty()) {
            System.err.println(
                "Usage: DeltaProfileReviewer [--skip-optional] [--auto-schema-defaults]"
              + " <candidateDeltaFile> [outputProfileFile]");
            System.exit(2);
        }

        Path inputPath = Paths.get(positional.get(0));
        if (!Files.exists(inputPath)) {
            System.err.println("File not found: " + inputPath);
            System.exit(3);
        }

        // Load candidate delta
        DocumentBuilder db = newBuilder();
        Document cdDoc = db.parse(inputPath.toFile());
        Element cdRoot = cdDoc.getDocumentElement();

        String fromVer = cdRoot.getAttribute("from");
        String toVer   = cdRoot.getAttribute("to");
        String view    = cdRoot.getAttribute("view");
        String fromNs  = cdRoot.getAttribute("from-namespace");
        String toNs    = cdRoot.getAttribute("to-namespace");
        String fromDir = cdRoot.getAttribute("from-schema-dir");
        String toDir   = cdRoot.getAttribute("to-schema-dir");

        // Derive output path
        Path outputPath;
        if (positional.size() >= 2) {
            outputPath = Paths.get(positional.get(1));
        } else {
            String outName = (view.isEmpty() ? "conversion" : view)
                    + "-" + fromVer + "-to-" + toVer + ".xml";
            outputPath = inputPath.getParent() != null
                    ? inputPath.getParent().resolve(outName)
                    : Paths.get(outName);
        }

        // Count pending entries
        NodeList candidateList = cdRoot.getElementsByTagNameNS(CD_NS, "candidate");
        int total   = candidateList.getLength();
        int pending = 0;
        for (int i = 0; i < total; i++) {
            Element c = (Element) candidateList.item(i);
            if ("PENDING".equals(c.getAttribute("status"))) pending++;
        }

        System.out.println("=======================================================");
        System.out.println(" FpML Conversion Delta Profile Reviewer");
        System.out.println("=======================================================");
        System.out.printf(" Transition        : %s → %s  (view: %s)%n", fromVer, toVer,
                view.isEmpty() ? "any" : view);
        System.out.printf(" Candidates        : %d total,  %d pending review%n", total, pending);
        System.out.printf(" --skip-optional   : %s%n", skipOptional       ? "ON" : "off");
        System.out.printf(" --auto-schema-def : %s%n", autoSchemaDefaults ? "ON" : "off");
        if (!fromNs.isEmpty())  System.out.printf(" FROM namespace    : %s%n", fromNs);
        if (!fromDir.isEmpty()) System.out.printf(" FROM schema dir   : %s%n", fromDir);
        if (!toNs.isEmpty())    System.out.printf(" TO namespace      : %s%n", toNs);
        if (!toDir.isEmpty())   System.out.printf(" TO schema dir     : %s%n", toDir);
        System.out.printf(" Output            : %s%n", outputPath);
        System.out.println("=======================================================");
        System.out.println();
        System.out.println(" For each PENDING entry type:");
        System.out.println("   skip           — skip this entry (no default injected)");
        System.out.println("   <value>        — approve with this default value");
        System.out.println("   (press Enter)  — accept XSD schema default if shown");
        System.out.println("   (RENAMED_*)    — confirm or provide the new name, or skip");
        System.out.println();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        // ── Review loop ───────────────────────────────────────────────────────
        int autoApproved = 0;
        int autoSkipped  = 0;
        int approved     = 0;
        int skipped      = 0;
        int renamed      = 0;
        Set<String> allUnknownAttrs = new LinkedHashSet<>();

        for (int i = 0; i < total; i++) {
            Element cand      = (Element) candidateList.item(i);
            String  status     = cand.getAttribute("status");
            String  changeType = cand.getAttribute("change-type");
            String  constType  = cand.getAttribute("constraint-type");

            // ── Warn on unknown attributes ────────────────────────────────────
            NamedNodeMap attrMap = cand.getAttributes();
            for (int ai = 0; ai < attrMap.getLength(); ai++) {
                Node attrNode = attrMap.item(ai);
                String attrName = attrNode.getLocalName();
                if (attrName == null) attrName = attrNode.getNodeName();
                // Skip namespace declarations
                if ("xmlns".equals(attrName) || attrName.startsWith("xmlns:")) continue;
                if (!KNOWN_ATTRS.contains(attrName)) {
                    allUnknownAttrs.add(attrName);
                }
            }

            // ── DROPPED_* are always auto-skipped ────────────────────────────
            if (changeType.startsWith("DROPPED_")) {
                if ("PENDING".equals(status)) {
                    cand.setAttribute("status",        "SKIPPED");
                    cand.setAttribute("reviewer-note", "auto-skipped: dropped node");
                    autoSkipped++;
                }
                continue;
            }

            if (!"PENDING".equals(status)) continue;  // already reviewed

            String schemaDefault = cand.getAttribute("schema-default");
            String sourceXsd     = cand.getAttribute("source-xsd");

            // ── --skip-optional: batch-skip schema-optional ──────────────────
            if (skipOptional && "schema-optional".equals(constType)) {
                cand.setAttribute("status",        "SKIPPED");
                cand.setAttribute("reviewer-note", "auto-skipped: schema-optional (--skip-optional)");
                autoSkipped++;
                continue;
            }

            // ── --auto-schema-defaults: approve schema-required w/ XSD default
            if (autoSchemaDefaults
                    && "schema-required".equals(constType)
                    && !schemaDefault.isEmpty()) {
                String xpath = cand.getAttribute("suggested-xpath");
                cand.setAttribute("status",                  "APPROVED");
                cand.setAttribute("reviewer-value",          schemaDefault);
                cand.setAttribute("reviewer-xpath",          xpath);
                cand.setAttribute("reviewer-scope",          view.isEmpty() ? "" : view);
                cand.setAttribute("reviewer-product",        "");
                cand.setAttribute("reviewer-only-if-absent", "true");
                cand.setAttribute("reviewer-note",
                        "auto-approved: schema-default=" + schemaDefault
                        + " (--auto-schema-defaults)");
                autoApproved++;
                System.out.printf("  [%s/%d] AUTO-APPROVED  %s/%s  value='%s'%n",
                        cand.getAttribute("id"), total,
                        cand.getAttribute("type-name"), cand.getAttribute("node-name"),
                        schemaDefault);
                continue;
            }

            // ── Interactive prompt ────────────────────────────────────────────
            System.out.println("-------------------------------------------------------");
            System.out.printf(" [%s/%d] %s%n",
                    cand.getAttribute("id"), total, changeType);
            System.out.printf("   Type/Node   : %s / %s%n",
                    cand.getAttribute("type-name"),
                    cand.getAttribute("node-name"));
            System.out.printf("   Constraint  : %s (%s)%n",
                    constType,
                    cand.getAttribute("target-constraint"));
            if (!schemaDefault.isEmpty())
                System.out.printf("   XSD default : %s%n", schemaDefault);
            System.out.printf("   XPath (suggested) : %s%n",
                    cand.getAttribute("suggested-xpath"));
            if (!sourceXsd.isEmpty())
                System.out.printf("   Source XSD  : %s%n", sourceXsd);

            // Warn about any unknown attributes seen on this candidate
            {
                List<String> unknownsHere = new ArrayList<>();
                for (int ai = 0; ai < attrMap.getLength(); ai++) {
                    Node an = attrMap.item(ai);
                    String an2 = an.getLocalName(); if (an2 == null) an2 = an.getNodeName();
                    if ("xmlns".equals(an2) || an2.startsWith("xmlns:")) continue;
                    if (!KNOWN_ATTRS.contains(an2)) unknownsHere.add(an2 + "=" + an.getNodeValue());
                }
                if (!unknownsHere.isEmpty())
                    System.out.printf("   [WARN] Unknown attributes: %s%n", String.join(", ", unknownsHere));
            }
            System.out.println();

            // ── RENAMED_* branch ──────────────────────────────────────────────
            if (changeType.startsWith("RENAMED_")) {
                String suggestedNewName = cand.getAttribute("suggested-name");
                String prompt = "  Confirm rename to '"
                        + (suggestedNewName.isEmpty() ? "?" : suggestedNewName)
                        + "' [Y / <new-name> / skip]: ";
                String answer = prompt(console, prompt);
                if ("skip".equalsIgnoreCase(answer.trim())) {
                    cand.setAttribute("status", "SKIPPED");
                    skipped++;
                    System.out.println("  → SKIPPED");
                    System.out.println();
                    continue;
                }
                String confirmedName = answer.trim().isEmpty() ? suggestedNewName
                        : "y".equalsIgnoreCase(answer.trim()) ? suggestedNewName
                        : answer.trim();
                if (confirmedName.isEmpty()) {
                    cand.setAttribute("status", "SKIPPED");
                    skipped++;
                    System.out.println("  No name provided — SKIPPED.");
                    System.out.println();
                    continue;
                }
                String note = prompt(console, "  Reviewer note [optional]: ");
                cand.setAttribute("status",            "REPLACED");
                cand.setAttribute("reviewer-rename-to", confirmedName);
                cand.setAttribute("reviewer-note",      note.trim());
                renamed++;
                System.out.printf("  → REPLACED  '%s' → '%s'%n",
                        cand.getAttribute("node-name"), confirmedName);
                System.out.println();
                continue;
            }

            // --- Value prompt ---
            String value = prompt(console,
                    "  Default value [skip"
                    + (schemaDefault.isEmpty() ? "" : " / Enter=" + schemaDefault)
                    + "]: ");
            if ("skip".equalsIgnoreCase(value.trim())) {
                cand.setAttribute("status", "SKIPPED");
                skipped++;
                System.out.println("  → SKIPPED");
                System.out.println();
                continue;
            }
            if (value.trim().isEmpty() && !schemaDefault.isEmpty()) {
                value = schemaDefault;
            }
            if (value.trim().isEmpty()) {
                // For schema-required fields with no value, offer prompt-at-runtime
                // instead of a plain SKIP so the conversion runtime can ask the operator.
                if ("schema-required".equals(constType)) {
                    String par = prompt(console,
                            "  No value — mark as 'prompt-at-runtime'? [Y/n]: ");
                    if (!par.trim().equalsIgnoreCase("n")) {
                        String xpath = cand.getAttribute("suggested-xpath");
                        cand.setAttribute("status",                     "APPROVED");
                        cand.setAttribute("reviewer-value",             "");
                        cand.setAttribute("reviewer-xpath",             xpath);
                        cand.setAttribute("reviewer-scope",             view.isEmpty() ? "" : view);
                        cand.setAttribute("reviewer-product",           "");
                        cand.setAttribute("reviewer-only-if-absent",    "true");
                        cand.setAttribute("reviewer-prompt-at-runtime", "true");
                        cand.setAttribute("reviewer-note",
                                "prompt-at-runtime: value must be supplied during conversion");
                        approved++;
                        System.out.println("  → APPROVED (prompt-at-runtime)");
                        System.out.println();
                        continue;
                    }
                }
                System.out.println("  No value entered — SKIPPED.");
                cand.setAttribute("status", "SKIPPED");
                skipped++;
                System.out.println();
                continue;
            }

            // --- XPath override ---
            String suggestedXpath = cand.getAttribute("suggested-xpath");
            String xpathInput = prompt(console,
                    "  XPath [Enter to accept: " + suggestedXpath + "]: ");
            String finalXpath = xpathInput.trim().isEmpty()
                    ? suggestedXpath : xpathInput.trim();

            // --- Scope: view ---
            String scopeView = prompt(console,
                    "  View scope [" + (view.isEmpty() ? "any" : view) + "]: ");
            String finalView = scopeView.trim().isEmpty()
                    ? (view.isEmpty() ? null : view)
                    : scopeView.trim();

            // --- Scope: product-type ---
            String scopeProd = prompt(console,
                    "  Product-type scope [any]: ");
            String finalProd = scopeProd.trim().isEmpty() ? null : scopeProd.trim();

            // --- only-if-absent ---
            String onlyIfInput = prompt(console,
                    "  Only inject if absent? [Y/n]: ");
            boolean onlyIfAbsent = !onlyIfInput.trim().equalsIgnoreCase("n");

            // --- Note ---
            String note = prompt(console, "  Reviewer note [optional]: ");

            // Record approval
            cand.setAttribute("status",                  "APPROVED");
            cand.setAttribute("reviewer-value",          value.trim());
            cand.setAttribute("reviewer-xpath",          finalXpath);
            cand.setAttribute("reviewer-scope",          finalView != null ? finalView : "");
            cand.setAttribute("reviewer-product",        finalProd != null ? finalProd : "");
            cand.setAttribute("reviewer-only-if-absent", String.valueOf(onlyIfAbsent));
            cand.setAttribute("reviewer-note",           note.trim());
            approved++;
            System.out.printf("  → APPROVED  value='%s'%n", value.trim());
            System.out.println();
        }

        // Save reviewed candidate delta back (preserves the review for re-runs)
        saveXml(cdDoc, inputPath);

        // Build and write conversion-delta.xml
        Document profileDoc = buildProfile(cdDoc, fromVer, toVer, view);
        saveXml(profileDoc, outputPath);

        System.out.println("=======================================================");
        System.out.printf(" Auto-approved  (schema defaults): %d%n", autoApproved);
        System.out.printf(" Auto-skipped   (optional/dropped): %d%n", autoSkipped);
        System.out.printf(" Human-approved : %d%n", approved);
        System.out.printf(" Human-renamed  : %d%n", renamed);
        System.out.printf(" Human-skipped  : %d%n", skipped);
        System.out.printf(" Total processed: %d%n",
                autoApproved + autoSkipped + approved + renamed + skipped);
        if (!allUnknownAttrs.isEmpty())
            System.out.printf(" [WARN] Unknown candidate attributes seen: %s%n",
                    String.join(", ", allUnknownAttrs));
        System.out.println(" Profile written: " + outputPath);
        System.out.println("=======================================================");
    }

    // =========================================================================
    // Profile generation
    // =========================================================================

    private static Document buildProfile(Document cdDoc,
                                         String fromVer, String toVer,
                                         String view) throws Exception {
        Element cdRoot = cdDoc.getDocumentElement();
        NodeList candidates = cdRoot.getElementsByTagNameNS(CD_NS, "candidate");

        // Collect approved and replaced entries
        List<Element> approved = new ArrayList<>();
        List<Element> replaced = new ArrayList<>();
        for (int i = 0; i < candidates.getLength(); i++) {
            Element c = (Element) candidates.item(i);
            if ("APPROVED".equals(c.getAttribute("status")))
                approved.add(c);
            else if ("REPLACED".equals(c.getAttribute("status")))
                replaced.add(c);
        }

        DocumentBuilder db = newBuilder();
        Document doc = db.newDocument();

        // Build comment header
        String comment = String.format(
            "%n  Conversion delta profile: FpML %s %s → %s %s%n"
          + "  Generated by DeltaProfileReviewer on %s%n"
          + "  %d conditional default(s) reviewed and approved.%n"
          + "  %d rename(s) confirmed.%n"
          + "  Review / adjust xpath and value before deploying to production.%n  ",
            fromVer, view.isEmpty() ? "" : view,
            toVer,   view.isEmpty() ? "" : view,
            LocalDate.now(), approved.size(), replaced.size());

        Element root = doc.createElementNS(DELTA_NS, "conversion-delta");
        root.setAttribute("from", fromVer);
        root.setAttribute("to",   toVer);
        if (!view.isEmpty()) {
            root.setAttribute("from-view", view);
            root.setAttribute("to-view",   view);
        }
        root.setAttribute("xmlns", DELTA_NS);
        doc.appendChild(root);
        root.appendChild(doc.createComment(comment));

        if (approved.isEmpty() && replaced.isEmpty()) {
            root.appendChild(doc.createComment(
                "\n  No conditional defaults were approved in this review session.\n  "));
            return doc;
        }

        // Build <conditional-defaults>
        Element cdElem = doc.createElementNS(DELTA_NS, "conditional-defaults");
        root.appendChild(cdElem);

        // Emit <insert> entries for APPROVED
        for (Element c : approved) {
            String  xpath        = c.getAttribute("reviewer-xpath");
            String  value        = c.getAttribute("reviewer-value");
            String  scopeView    = c.getAttribute("reviewer-scope");
            String  scopeProd    = c.getAttribute("reviewer-product");
            boolean onlyIfAbsent = !"false".equalsIgnoreCase(
                    c.getAttribute("reviewer-only-if-absent"));
            boolean promptAtRuntime = "true".equalsIgnoreCase(
                    c.getAttribute("reviewer-prompt-at-runtime"));
            String  note         = c.getAttribute("reviewer-note");
            String  cType        = c.getAttribute("constraint-type");

            cdElem.appendChild(doc.createComment(
                " " + c.getAttribute("change-type") + ": "
                + c.getAttribute("type-name") + "/"
                + c.getAttribute("node-name") + " "));

            Element insert = doc.createElementNS(DELTA_NS, "insert");
            insert.setAttribute("xpath",           xpath);
            insert.setAttribute("value",           value);
            insert.setAttribute("only-if-absent",  String.valueOf(onlyIfAbsent));
            if (promptAtRuntime)
                insert.setAttribute("prompt-at-runtime", "true");
            if (!scopeView.isEmpty())  insert.setAttribute("view",            scopeView);
            if (!scopeProd.isEmpty())  insert.setAttribute("product-type",    scopeProd);
            if (!cType.isEmpty())      insert.setAttribute("constraint-type", cType);
            if (!note.isEmpty())       insert.setAttribute("note",            note);
            cdElem.appendChild(insert);
        }

        // Emit <rename> entries for REPLACED
        for (Element c : replaced) {
            String fromName = c.getAttribute("node-name");
            String toName   = c.getAttribute("reviewer-rename-to");
            String cType    = c.getAttribute("change-type");
            String note     = c.getAttribute("reviewer-note");
            String xpath    = c.getAttribute("suggested-xpath");

            cdElem.appendChild(doc.createComment(
                " " + cType + ": "
                + c.getAttribute("type-name") + " "
                + fromName + " → " + toName + " "));

            Element rename = doc.createElementNS(DELTA_NS, "rename");
            rename.setAttribute("from",  fromName);
            rename.setAttribute("to",    toName);
            if (!xpath.isEmpty())  rename.setAttribute("xpath", xpath);
            if (!note.isEmpty())   rename.setAttribute("note",  note);
            cdElem.appendChild(rename);
        }

        return doc;
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static String prompt(BufferedReader reader, String message)
            throws IOException {
        System.out.print(message);
        System.out.flush();
        String line = reader.readLine();
        return line != null ? line : "";
    }

    private static void saveXml(Document doc, Path path) throws Exception {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.INDENT,   "yes");
        tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            tf.transform(new DOMSource(doc), new StreamResult(fos));
        }
    }

    private static DocumentBuilder newBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder();
    }
}

