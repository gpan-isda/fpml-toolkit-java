// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end showcase of the interactive <b>probe → fill → convert</b> flow
 * using the bundled test sample {@code test-sample/test_convert-FpML.xml}
 * (FpML 4-3 swaption → FpML 5-13 confirmation).
 *
 * <h2>Flow demonstrated</h2>
 * <pre>
 *  Step 1 — PROBE
 *    FpMLConversionService.probe(xml, "5-13", "confirmation")
 *      ├─ Runs inbound XSD + business-rule (AllRules) validation on the 4-3 source
 *      ├─ Runs a dry-run conversion with RecordingRuntimeValueProvider
 *      └─ Returns ProbeResult:
 *           • isComplete()                → true if no user input needed
 *           • getSourceValidationErrors() → XSD / business-rule problems in source doc
 *           • getRequiredFields()         → HELPER_VALUE / RUNTIME_PROMPT fields needed
 *
 *  Step 2 — FILL  (only if !isComplete())
 *    Caller inspects getRequiredFields() / getSourceValidationErrors()
 *    and supplies answers as Map&lt;String,String&gt;
 *
 *  Step 3 — CONVERT
 *    FpMLConversionService.convert(xml, "5-13", "confirmation", answers)
 *      └─ Returns PipelineResult: converted document + inbound/outbound errors
 * </pre>
 *
 * <p>Run with: {@code mvnd test -Dtest=InteractiveConversionFlowTest}</p>
 *
 * @author ISDA FpML Team
 * @see FpMLConversionService
 * @see ProbeResult
 * @see PipelineResult
 */
@DisplayName("Interactive Probe → Fill → Convert Flow  [test-sample/test_convert-FpML.xml]")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InteractiveConversionFlowTest extends ToolkitTestBase {

    private static final String SAMPLE_PATH    = "test-sample/test_convert-FpML.xml";
    private static final String TARGET_VERSION = "5-13";
    private static final String TARGET_VIEW    = "confirmation";

    // =========================================================================
    // Step 1 — PROBE
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Step 1 — probe() reveals source validation errors and/or required fields")
    void step1_probe_revealsRequirementsBeforeConversion() throws Exception {

        String xml = readSample();

        banner("STEP 1: PROBE", SAMPLE_PATH + " → " + TARGET_VERSION);

        ProbeResult probe = FpMLConversionService.INSTANCE.probe(xml, TARGET_VERSION, TARGET_VIEW);

        // ── Source validation errors ──────────────────────────────────────────
        System.out.println("\n[SOURCE VALIDATION ERRORS — XSD + business-rules on source doc]");
        List<String> sourceErrors = probe.getSourceValidationErrors();
        if (sourceErrors.isEmpty()) {
            System.out.println("  (none — source document passes all inbound checks)");
        } else {
            sourceErrors.forEach(e -> System.out.println("  ✗ " + e));
        }

        // ── Required fields ───────────────────────────────────────────────────
        System.out.println("\n[REQUIRED FIELDS — user must supply before conversion]");
        List<ProbeResult.RequiredField> fields = probe.getRequiredFields();
        if (fields.isEmpty()) {
            System.out.println("  (none — no additional values needed)");
        } else {
            for (ProbeResult.RequiredField f : fields) {
                System.out.printf("  • [%-22s] key=%-28s label=%s%n",
                        f.getKind(), f.getName(), f.getLabel());
            }
        }

        // ── Probe outcome ─────────────────────────────────────────────────────
        System.out.println("\n[PROBE OUTCOME]");
        if (probe.isComplete()) {
            PipelineResult pr = probe.getConversionResult();
            System.out.println("  ✓ COMPLETE — conversion succeeded in probe pass.");
            System.out.println("    Source  : " + versionOf(pr.getSourceRelease()));
            System.out.println("    Target  : " + versionOf(pr.getTargetRelease()));
            if (!pr.getInboundErrors().isEmpty())
                pr.getInboundErrors().forEach(e -> System.out.println("    inbound : " + e));
            if (!pr.getOutboundErrors().isEmpty())
                pr.getOutboundErrors().forEach(e -> System.out.println("    outbound: " + e));
        } else {
            System.out.println("  ⚠ INCOMPLETE — caller must act before conversion.");
            if (probe.hasSourceValidationErrors())
                System.out.println("    → Source document has " + sourceErrors.size()
                        + " validation error(s) that must be fixed.");
            if (!fields.isEmpty())
                System.out.println("    → " + fields.size() + " required field(s) not yet supplied.");
        }

        footer("STEP 1 COMPLETE");
        assertNotNull(probe, "probe() must return a non-null result");
    }

    // =========================================================================
    // Step 2 + 3 — FILL then CONVERT
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("Step 2+3 — simulate UI filling required fields, then convert to 5-13")
    void step2_and_step3_fillAndConvert() throws Exception {

        String xml = readSample();

        banner("STEP 2+3: FILL → CONVERT", SAMPLE_PATH + " → " + TARGET_VERSION);

        // ── Step 2: probe to discover what's needed ───────────────────────────
        ProbeResult probe = FpMLConversionService.INSTANCE.probe(xml, TARGET_VERSION, TARGET_VIEW);

        Map<String, String> answers = new HashMap<>();

        if (!probe.isComplete()) {

            System.out.println("\n[STEP 2 — FILLING REQUIRED FIELDS (simulating UI input)]");
            for (ProbeResult.RequiredField f : probe.getRequiredFields()) {
                String simulatedValue = simulateUserInput(f);
                if (simulatedValue != null) {
                    answers.put(f.getName(), simulatedValue);
                    System.out.printf("  → supplied [%-22s] %-28s = \"%s\"%n",
                            f.getKind(), f.getName(), simulatedValue);
                } else {
                    System.out.printf("  ✗ cannot fill  [%-22s] %-28s — must fix source document%n",
                            f.getKind(), f.getName());
                }
            }

            if (probe.hasSourceValidationErrors()) {
                System.out.println("\n[SOURCE VALIDATION ERRORS — must be fixed in source document]");
                probe.getSourceValidationErrors()
                     .forEach(e -> System.out.println("  ✗ " + e));
                System.out.println("\n  NOTE: Proceeding with conversion anyway to show what happens.");
            }

        } else {
            System.out.println("\n  Probe was COMPLETE — no fields to fill, conversion already ran.");
        }

        // ── Step 3: convert with supplied values ──────────────────────────────
        System.out.println("\n[STEP 3 — CONVERT with " + answers.size() + " supplied value(s)]");
        PipelineResult result = FpMLConversionService.INSTANCE.convert(
                xml, TARGET_VERSION, TARGET_VIEW, answers);

        System.out.println("  isSuccess    : " + result.isSuccess());
        System.out.println("  source       : " + versionOf(result.getSourceRelease()));
        System.out.println("  target       : " + versionOf(result.getTargetRelease()));

        if (!result.getErrors().isEmpty()) {
            System.out.println("\n  [PIPELINE ERRORS]");
            result.getErrors().forEach(e -> System.out.println("    ✗ " + e));
        }
        if (!result.getInboundErrors().isEmpty()) {
            System.out.println("\n  [INBOUND ERRORS (source validation)]");
            result.getInboundErrors().forEach(e -> System.out.println("    ✗ " + e));
        }
        if (!result.getOutboundErrors().isEmpty()) {
            System.out.println("\n  [OUTBOUND ERRORS (target validation)]");
            result.getOutboundErrors().forEach(e -> System.out.println("    ✗ " + e));
        }
        if (!result.getDroppedFields().isEmpty()) {
            System.out.println("\n  [DROPPED FIELDS (not carried forward)]");
            result.getDroppedFields().forEach(f -> System.out.println("    - " + f));
        }
        if (result.isSuccess() && result.getDocument() != null) {
            String convertedXml = serializeToXml(result);
            System.out.println("\n  [CONVERTED XML — first 1000 chars]");
            System.out.println(convertedXml.substring(0, Math.min(1000, convertedXml.length())));
        }

        footer("STEP 2+3 COMPLETE");

        // ── Assertions ─────────────────────────────────────────────────────────
        assertTrue(result.isSuccess(),
                "Conversion should succeed. Pipeline errors: " + result.getErrors());
        assertNotNull(result.getDocument(),
                "Converted document must not be null");
        assertNotNull(result.getTargetRelease(),
                "Target release must be identified in converted document");
        assertTrue(result.getTargetRelease().getVersion().startsWith("5-13"),
                "Target release should be 5-13 but was: " + result.getTargetRelease().getVersion());
    }

    // =========================================================================
    // Validate-only (no conversion)
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("Validate-only — validate() surfaces all XSD and business-rule errors")
    void validateOnly_surfacesSourceErrors() throws Exception {

        String xml = readSample();

        banner("VALIDATE-ONLY (no conversion)", SAMPLE_PATH);

        PipelineResult result = FpMLConversionService.INSTANCE.validate(xml);

        System.out.println("  isSuccess    : " + result.isSuccess());
        System.out.println("  sourceVersion: " + versionOf(result.getSourceRelease()));

        System.out.println("\n[INBOUND ERRORS (XSD + business-rules)]");
        List<String> errors = result.getInboundErrors();
        if (errors.isEmpty()) {
            System.out.println("  (none — source document is fully valid)");
        } else {
            errors.forEach(e -> System.out.println("  ✗ " + e));
        }

        footer("VALIDATE-ONLY COMPLETE");
        assertNotNull(result, "validate() must return a non-null result");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Simulates a UI/caller supplying a value for a required field.
     *
     * <ul>
     *   <li>{@code HELPER_VALUE}          → {@code "USD"} (sensible currency default)</li>
     *   <li>{@code RUNTIME_PROMPT}        → {@code "SIMULATED-VALUE"} (generic placeholder)</li>
     *   <li>{@code SOURCE_VALIDATION_ERROR} → {@code null} (cannot be filled; must fix source)</li>
     * </ul>
     */
    private static String simulateUserInput(ProbeResult.RequiredField f) {
        switch (f.getKind()) {
            case HELPER_VALUE:    return "USD";
            case RUNTIME_PROMPT:  return "SIMULATED-VALUE";
            default:              return null;  // SOURCE_VALIDATION_ERROR — unfillable
        }
    }

    /** Reads the test sample file from disk (must run from project root). */
    private static String readSample() throws Exception {
        File f = new File(SAMPLE_PATH);
        assertTrue(f.exists(),
                "Test sample not found: " + SAMPLE_PATH
                + "\nMake sure tests run from project root. user.dir="
                + System.getProperty("user.dir"));
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }

    /** Serializes the converted document to an indented XML string. */
    private static String serializeToXml(PipelineResult result) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(result.getDocument()), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            return "(serialization failed: " + e.getMessage() + ")";
        }
    }

    /** Returns the version string of a release, or {@code "(unknown)"} if null. */
    private static String versionOf(com.handcoded.meta.Release r) {
        return r != null ? r.getVersion() : "(unknown)";
    }

    private static void banner(String title, String subtitle) {
        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("  " + title);
        System.out.println("  " + subtitle);
        System.out.println("=".repeat(72));
    }

    private static void footer(String label) {
        System.out.println();
        System.out.println("  ✓ " + label);
        System.out.println("=".repeat(72));
        System.out.println();
    }
}

