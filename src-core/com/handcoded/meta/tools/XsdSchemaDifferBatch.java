package com.handcoded.meta.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs {@link XsdSchemaDiffer} for every consecutive FpML version pair
 * (all views) in a single JVM, writing candidate-delta.xml files to the
 * configured output directory.
 *
 * <h3>Covered pairs</h3>
 * <ul>
 *   <li>All consecutive FpML 4.x pairs (flat schema dirs, view = "all").</li>
 *   <li>The FpML 4-10 → 5-0 bridge (flat → confirmation / reporting).</li>
 *   <li>All consecutive FpML 5.x pairs for every view common to both versions.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 *   java com.handcoded.meta.tools.XsdSchemaDifferBatch \
 *        [schemaBaseDir]  [outputDir]
 *
 *   Defaults:
 *     schemaBaseDir = files-fpml/schemas
 *     outputDir     = files-fpml/conversion-profiles
 * </pre>
 *
 * @author ISDA FpML Team
 * @since  TFP 1.x
 */
public final class XsdSchemaDifferBatch {

    private static final Logger LOG =
            Logger.getLogger(XsdSchemaDifferBatch.class.getName());

    public static void main(String[] args) throws Exception {
        // Suppress verbose JVM logging for cleaner console output
        Logger.getLogger("").setLevel(Level.WARNING);

        Path schemaBase = Paths.get(args.length > 0 ? args[0] : "files-fpml/schemas");
        Path outputDir  = Paths.get(args.length > 1 ? args[1] : "files-fpml/conversion-profiles");

        if (!Files.isDirectory(schemaBase)) {
            System.err.println("Schema base directory not found: " + schemaBase);
            System.exit(1);
        }
        Files.createDirectories(outputDir);

        int ok = 0, fail = 0;
        List<String[]> skipped = new ArrayList<>();

        // ─── 1. FpML 4.x consecutive pairs (flat schema dirs) ────────────────
        System.out.println();
        System.out.println("=== FpML 4.x consecutive pairs (flat) ===");
        String[] v4 = {"4-0","4-1","4-2","4-3","4-4","4-5","4-6","4-7","4-8","4-9","4-10"};
        for (int i = 0; i < v4.length - 1; i++) {
            String fv = v4[i], tv = v4[i+1];
            Path fd = schemaBase.resolve("fpml" + fv);
            Path td = schemaBase.resolve("fpml" + tv);
            if (!Files.isDirectory(fd) || !Files.isDirectory(td)) {
                skipped.add(new String[]{fv, tv, "all", "missing dir"});
                System.out.printf("  %-30s  SKIP (dir missing)%n", fv + " → " + tv + " [all]");
                continue;
            }
            int r = runDiff(fd, td, fv, tv, "all", outputDir);
            if (r == 0) ok++; else fail++;
        }

        // ─── 2. FpML 4-10 → 5-0 bridge ──────────────────────────────────────
        System.out.println();
        System.out.println("=== FpML 4-10 → 5-0 bridge ===");
        Path fd410 = schemaBase.resolve("fpml4-10");
        for (String view : new String[]{"confirmation","reporting"}) {
            Path td = schemaBase.resolve("fpml5-0").resolve(view);
            if (!Files.isDirectory(fd410) || !Files.isDirectory(td)) {
                skipped.add(new String[]{"4-10","5-0", view, "missing dir"});
                System.out.printf("  %-30s  SKIP (dir missing)%n", "4-10 → 5-0 [" + view + "]");
                continue;
            }
            int r = runDiff(fd410, td, "4-10", "5-0", view, outputDir);
            if (r == 0) ok++; else fail++;
        }

        // ─── 3. FpML 5.x consecutive pairs (per view) ────────────────────────
        System.out.println();
        System.out.println("=== FpML 5.x consecutive pairs (per view) ===");
        String[] v5 = {
            "5-0","5-1","5-2","5-3","5-4","5-5","5-6",
            "5-7","5-8","5-9","5-10","5-11","5-12","5-13"
        };
        for (int i = 0; i < v5.length - 1; i++) {
            String fv = v5[i], tv = v5[i+1];
            Path fdb = schemaBase.resolve("fpml" + fv);
            Path tdb = schemaBase.resolve("fpml" + tv);
            if (!Files.isDirectory(fdb) || !Files.isDirectory(tdb)) {
                skipped.add(new String[]{fv, tv, "*", "missing dir"});
                System.out.printf("  %-30s  SKIP (dir missing)%n", fv + " → " + tv);
                continue;
            }
            // Collect views common to both versions
            Set<String> fromViews = viewsIn(fdb);
            Set<String> toViews   = viewsIn(tdb);
            Set<String> common    = new LinkedHashSet<>(fromViews);
            common.retainAll(toViews);

            if (common.isEmpty()) {
                skipped.add(new String[]{fv, tv, "*", "no common views"});
                System.out.printf("  %-30s  SKIP (no common views)%n", fv + " → " + tv);
                continue;
            }
            for (String view : common) {
                Path fd = fdb.resolve(view);
                Path td = tdb.resolve(view);
                int r = runDiff(fd, td, fv, tv, view, outputDir);
                if (r == 0) ok++; else fail++;
            }
        }

        // ─── Summary ──────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.printf(" Generation complete:  %d OK,  %d failed,  %d skipped%n",
                ok, fail, skipped.size());
        System.out.println(" Output: " + outputDir.toAbsolutePath());
        System.out.println("=".repeat(60));
        if (fail > 0) System.exit(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int runDiff(Path fromDir, Path toDir,
                                String fromVer, String toVer, String view,
                                Path outputDir) {
        String label  = String.format("%-30s", fromVer + " → " + toVer + " [" + view + "]");
        Path   output = outputDir.resolve(
                "candidate-" + fromVer + "-to-" + toVer + "-" + view + ".xml");
        System.out.printf("  %s  ", label);
        System.out.flush();

        try {
            XsdSchemaDiffer.SchemaModel fromModel =
                    XsdSchemaDiffer.parseSchemaDir(fromDir);
            XsdSchemaDiffer.SchemaModel toModel   =
                    XsdSchemaDiffer.parseSchemaDir(toDir);

            List<XsdSchemaDiffer.Candidate> candidates =
                    XsdSchemaDiffer.computeDelta(fromModel, toModel);

            XsdSchemaDiffer.writeCandidateDelta(candidates, fromVer, toVer, view, output);

            long countNew = candidates.stream().filter(c ->
                    c.changeType == XsdSchemaDiffer.Candidate.ChangeType.NEW_ATTRIBUTE
                 || c.changeType == XsdSchemaDiffer.Candidate.ChangeType.REQUIRED_ATTRIBUTE
                 || c.changeType == XsdSchemaDiffer.Candidate.ChangeType.NEW_ELEMENT
                 || c.changeType == XsdSchemaDiffer.Candidate.ChangeType.REQUIRED_ELEMENT)
                    .count();
            long countDrop = candidates.size() - countNew;

            System.out.printf("OK  (%3d additions/changes,  %3d dropped)%n",
                    countNew, countDrop);
            return 0;

        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            LOG.log(Level.WARNING, "Diff failed for " + fromVer + " → " + toVer
                    + " [" + view + "]", e);
            return 1;
        }
    }

    private static Set<String> viewsIn(Path dir) throws Exception {
        Set<String> views = new LinkedHashSet<>();
        try (var ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds)
                if (Files.isDirectory(p)) views.add(p.getFileName().toString());
        }
        return views;
    }
}

