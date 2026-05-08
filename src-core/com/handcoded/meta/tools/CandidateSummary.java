package com.handcoded.meta.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans a directory of {@code candidate-*.xml} files (produced by
 * {@link XsdSchemaDifferBatch} / {@link XsdSchemaDiffer}) and prints a
 * prioritised review-order table showing:
 *
 * <ul>
 *   <li><b>Req(nd)</b> — {@code schema-required} candidates with <em>no</em>
 *       XSD-declared default: these genuinely need a human decision.</li>
 *   <li><b>Req(sd)</b> — {@code schema-required} candidates that <em>have</em>
 *       an XSD default: can be auto-approved with {@code --auto-schema-defaults}.</li>
 *   <li><b>Opt</b>     — {@code schema-optional} candidates: can be batch-skipped
 *       with {@code --skip-optional}.</li>
 *   <li><b>Dropped</b> — {@code DROPPED_*} informational entries: auto-skipped.</li>
 *   <li><b>Approved / Skipped</b> — already reviewed entries.</li>
 * </ul>
 *
 * <p>Files are sorted by descending FpML version then by descending
 * {@code Req(nd)} count, so the most actionable work appears first.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   java com.handcoded.meta.tools.CandidateSummary [scanDir]
 *
 *   Default scanDir: files-fpml/conversion-profiles
 * </pre>
 *
 * @author ISDA FpML Team
 * @since  TFP 1.x
 */
public final class CandidateSummary {

    private static final String CD_NS =
            "http://www.handcoded.com/fpml/candidate-delta";

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // Force UTF-8 output so box-drawing and arrow characters display correctly
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        Path scanDir = Paths.get(args.length > 0 ? args[0]
                : "files-fpml/conversion-profiles");

        if (!Files.isDirectory(scanDir)) {
            System.err.println("Directory not found: " + scanDir);
            System.exit(1);
        }

        // ── Collect candidate-*.xml files ────────────────────────────────────
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds =
                     Files.newDirectoryStream(scanDir, "candidate-*.xml")) {
            for (Path p : ds) files.add(p);
        }

        if (files.isEmpty()) {
            System.out.println("No candidate-*.xml files found in: " + scanDir);
            return;
        }

        // ── Parse and build row data ─────────────────────────────────────────
        DocumentBuilder db = newBuilder();
        List<Row> rows     = new ArrayList<>();
        for (Path f : files) {
            rows.add(parse(f, db));
        }

        // ── Sort: most-recent FpML version first, then most req(nd) first ────
        rows.sort(Comparator
                .<Row, String>comparing(r -> r.sortKey, Comparator.reverseOrder())
                .thenComparingInt((Row r) -> r.reqNoDefault).reversed()
                .thenComparing(r -> r.view));

        // ── Print table ──────────────────────────────────────────────────────
        printTable(rows, scanDir);
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private static Row parse(Path file, DocumentBuilder db) {
        String filename = file.getFileName().toString();
        // candidate-<from>-to-<to>-<view>.xml
        String from = "?", to = "?", view = "?";
        String baseName = filename.replace("candidate-", "").replace(".xml", "");
        int toIdx = baseName.indexOf("-to-");
        if (toIdx >= 0) {
            from = baseName.substring(0, toIdx);
            String rest = baseName.substring(toIdx + 4); // after "-to-"
            // rest = "<to>-<view>"
            // <to> can be "5-13" or "4-10" — find the first "-" after the first digit group
            int viewSep = findViewSeparator(rest);
            if (viewSep >= 0) {
                to   = rest.substring(0, viewSep);
                view = rest.substring(viewSep + 1);
            } else {
                to = rest;
            }
        }

        // Build a sort key: pad minor versions so lexicographic order = version order
        String sortKey = versionSortKey(to) + "-" + view;

        int reqNoDefault = 0, reqWithDefault = 0, optional = 0, dropped = 0;
        int approved = 0, skipped = 0, pending = 0;

        try {
            Document doc  = db.parse(file.toFile());
            Element  root = doc.getDocumentElement();
            NodeList nl   = root.getElementsByTagNameNS(CD_NS, "candidate");
            if (nl.getLength() == 0) {
                // try without namespace (namespace-unaware parse)
                nl = root.getElementsByTagName("candidate");
            }

            for (int i = 0; i < nl.getLength(); i++) {
                Element c      = (Element) nl.item(i);
                String  status = c.getAttribute("status");
                String  chType = c.getAttribute("change-type");
                String  cType  = c.getAttribute("constraint-type");
                String  sdef   = c.getAttribute("schema-default");

                if ("APPROVED".equals(status)) { approved++; continue; }
                if ("SKIPPED" .equals(status)) { skipped++;  continue; }

                // PENDING below
                pending++;
                if (chType.startsWith("DROPPED_")) {
                    dropped++;
                } else if ("schema-required".equals(cType)) {
                if (!sdef.isEmpty()) reqWithDefault++;
                else                 reqNoDefault++;
                } else {
                    optional++;
                }
            }
        } catch (Exception e) {
            // leave counts at 0
        }

        int total = reqNoDefault + reqWithDefault + optional + dropped + approved + skipped;
        return new Row(filename, from, to, view, sortKey,
                reqNoDefault, reqWithDefault, optional, dropped,
                approved, skipped, pending, total);
    }

    /**
     * Finds the separator between the "to-version" part and the "view" part.
     * E.g. "5-13-confirmation" → separator at index 4 (after "5-13").
     * Version numbers look like N-N or N-NN.
     */
    private static int findViewSeparator(String s) {
        // match pattern: digit(s)-digit(s)-
        int i = 0;
        int len = s.length();
        // consume first digit group
        while (i < len && Character.isDigit(s.charAt(i))) i++;
        if (i < len && s.charAt(i) == '-') {
            i++; // consume '-'
            // consume second digit group
            int j = i;
            while (j < len && Character.isDigit(s.charAt(j))) j++;
            if (j > i && j < len && s.charAt(j) == '-') {
                return j; // separator between version and view
            }
        }
        return -1;
    }

    /** Zero-pads version parts so "4-10" > "4-9" lexicographically. */
    private static String versionSortKey(String ver) {
        // "5-13" → "05-13", "4-10" → "04-10"
        String[] parts = ver.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            try {
                sb.append(String.format("%03d", Integer.parseInt(p))).append('-');
            } catch (NumberFormatException e) {
                sb.append(p).append('-');
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Table printing
    // =========================================================================

    private static final String HEADER_FILE   = "Candidate File";
    private static final int    COL_FILE       = 52;
    private static final int    COL_NUM        = 8;

    private static void printTable(List<Row> rows, Path dir) {
        int totalReqNd   = rows.stream().mapToInt(r -> r.reqNoDefault).sum();
        int totalReqSd   = rows.stream().mapToInt(r -> r.reqWithDefault).sum();
        int totalOpt     = rows.stream().mapToInt(r -> r.optional).sum();
        int totalDrop    = rows.stream().mapToInt(r -> r.dropped).sum();
        int totalAppr    = rows.stream().mapToInt(r -> r.approved).sum();
        int totalSkip    = rows.stream().mapToInt(r -> r.skipped).sum();
        int totalPending = rows.stream().mapToInt(r -> r.pending).sum();
        int totalAll     = rows.stream().mapToInt(r -> r.total).sum();

        String line = "─".repeat(COL_FILE + COL_NUM * 7 + 4);

        System.out.println();
        System.out.println("=".repeat(line.length()));
        System.out.println("  FpML Conversion Delta — Candidate Summary");
        System.out.println("  Directory : " + dir.toAbsolutePath());
        System.out.println("  Files     : " + rows.size() + "  |  Total pending: " + totalPending);
        System.out.println("=".repeat(line.length()));
        System.out.printf("  %-" + COL_FILE + "s %8s %8s %8s %8s %8s %8s %8s%n",
                HEADER_FILE,
                "Req(nd)", "Req(sd)", "Opt", "Dropped", "Approved", "Skipped", "Total");
        System.out.println("  " + line);

        for (Row r : rows) {
            boolean needsHuman = r.pending > 0 && r.reqNoDefault > 0;
            String flag = needsHuman ? " *" : "  ";
            System.out.printf("  %-" + COL_FILE + "s %8d %8d %8d %8d %8d %8d %8d%s%n",
                    r.filename,
                    r.reqNoDefault, r.reqWithDefault, r.optional, r.dropped,
                    r.approved, r.skipped, r.total,
                    flag);
        }

        System.out.println("  " + line);
        System.out.printf("  %-" + COL_FILE + "s %8d %8d %8d %8d %8d %8d %8d%n",
                "TOTALS",
                totalReqNd, totalReqSd, totalOpt, totalDrop,
                totalAppr, totalSkip, totalAll);
        System.out.println("=".repeat(line.length()));
        System.out.println();
        System.out.println("  Legend:");
        System.out.println("    Req(nd)  — schema-required, NO xsd-default  → human decision needed");
        System.out.println("    Req(sd)  — schema-required, HAS xsd-default → auto via --auto-schema-defaults");
        System.out.println("    Opt      — schema-optional                  → auto via --skip-optional");
        System.out.println("    Dropped  — dropped nodes (info only)        → always auto-skipped");
        System.out.println("    *        — file still has PENDING entries that need human review");
        System.out.println();
        System.out.println("  Suggested review command (most-recent first):");
        System.out.println("    java com.handcoded.meta.tools.DeltaProfileReviewer \\");
        System.out.println("         --skip-optional --auto-schema-defaults \\");
        System.out.println("         files-fpml/conversion-profiles/<candidate-file>.xml");
        System.out.println();
    }

    // =========================================================================
    // Data
    // =========================================================================

    private static final class Row {
        final String filename;
        final String from, to, view, sortKey;
        final int reqNoDefault, reqWithDefault, optional, dropped;
        final int approved, skipped, pending, total;

        Row(String filename, String from, String to, String view, String sortKey,
            int reqNoDefault, int reqWithDefault, int optional, int dropped,
            int approved, int skipped, int pending, int total) {
            this.filename      = filename;
            this.from          = from;
            this.to            = to;
            this.view          = view;
            this.sortKey       = sortKey;
            this.reqNoDefault  = reqNoDefault;
            this.reqWithDefault = reqWithDefault;
            this.optional      = optional;
            this.dropped       = dropped;
            this.approved      = approved;
            this.skipped       = skipped;
            this.pending       = pending;
            this.total         = total;
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static DocumentBuilder newBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        // suppress parse warnings/errors to stderr
        db.setErrorHandler(null);
        return db;
    }
}

