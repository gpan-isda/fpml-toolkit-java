// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import com.handcoded.fpml.validation.AllRules;
import com.handcoded.meta.Release;
import com.handcoded.meta.Specification;
import com.handcoded.validation.RuleSet;
import com.handcoded.xml.XmlUtility;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Single-entry-point service façade for FpML document conversion and validation.
 *
 * <p>Callers interact with the toolkit through this class only — no Spring,
 * no reflection, no classpath scanning required by the consumer.  The class
 * is a plain Java singleton usable from any JVM-based framework.</p>
 *
 * <h3>Typical one-shot conversion (5.x → 5.x, no interactive prompts)</h3>
 * <pre>{@code
 * PipelineResult r = FpMLConversionService.INSTANCE.convert(fpmlXml, "5-13", "confirmation");
 * if (r.isSuccess()) {
 *     String converted = serialize(r.getDocument());
 * }
 * }</pre>
 *
 * <h3>Interactive probe → fill → convert loop (4.x FX docs)</h3>
 * <pre>{@code
 * ProbeResult probe = FpMLConversionService.INSTANCE.probe(fpmlXml, "5-13", "confirmation");
 * if (!probe.isComplete()) {
 *     Map<String,String> answers = collectFromUI(probe.getRequiredFields());
 *     PipelineResult r = FpMLConversionService.INSTANCE.convert(
 *             fpmlXml, "5-13", "confirmation", answers);
 * } else {
 *     PipelineResult r = probe.getConversionResult();
 * }
 * }</pre>
 *
 * <h3>Schema validation (no conversion)</h3>
 * <pre>{@code
 * PipelineResult r = FpMLConversionService.INSTANCE.validate(fpmlXml);
 * }</pre>
 *
 * <p>Instances are thread-safe.  The singleton {@link #INSTANCE} is safe for
 * shared use across multiple threads.</p>
 *
 * @author ISDA FpML Team
 * @see    FpMLConversionPipeline
 * @see    ProbeResult
 * @see    MapRuntimeValueProvider
 * @since  TFP 1.x
 */
public final class FpMLConversionService {

    // =========================================================================
    // Singleton
    // =========================================================================

    /** Shared singleton for non-DI callers. */
    public static final FpMLConversionService INSTANCE = new FpMLConversionService();

    /** Public constructor — allows explicit instantiation in DI containers. */
    public FpMLConversionService() { }

    // =========================================================================
    // Conversion API
    // =========================================================================

    /**
     * Converts an FpML XML string to the given target version.
     *
     * <p>The source release and view are auto-detected from the document.
     * A matching delta profile is looked up via
     * {@link ConversionDeltaProfileRegistry} (filesystem dir or classpath
     * fallback).  Schema validation is disabled by default; the pipeline
     * performs a pure structural + enrichment upgrade.</p>
     *
     * @param fpmlXml    Well-formed FpML XML string.
     * @param toVersion  Target version string, e.g. {@code "5-13"}.
     * @param view       Target view (e.g. {@code "confirmation"}), or
     *                   {@code null} to auto-detect from source.
     * @return A {@link PipelineResult} — never {@code null}.
     */
    public PipelineResult convert(String fpmlXml, String toVersion, String view) {
        return convert(fpmlXml, toVersion, view, (RuntimeValueProvider) null);
    }

    /**
     * Converts an FpML XML string with caller-supplied field values.
     *
     * <p>Closes the interactive probe→fill→convert loop: values from the caller
     * are wrapped in a {@link MapRuntimeValueProvider} and passed to the pipeline
     * so that {@code prompt-at-runtime} conditional-default entries are resolved
     * without blocking on stdin.</p>
     *
     * @param fpmlXml    Well-formed FpML XML string.
     * @param toVersion  Target version string.
     * @param view       Target view, or {@code null} to auto-detect.
     * @param userValues Caller-supplied field values keyed by field name or
     *                   XPath (matched by {@link MapRuntimeValueProvider}).
     *                   May be {@code null} or empty.
     * @return A {@link PipelineResult} — never {@code null}.
     */
    public PipelineResult convert(String fpmlXml, String toVersion, String view,
                                  Map<String, String> userValues) {
        RuntimeValueProvider provider = (userValues != null && !userValues.isEmpty())
                ? new MapRuntimeValueProvider(userValues) : null;
        return convert(fpmlXml, toVersion, view, provider);
    }

    /**
     * Converts an FpML XML string using an explicit delta profile (bypasses
     * the registry lookup).
     *
     * @param fpmlXml    Well-formed FpML XML string.
     * @param profile    Explicit delta profile to use.  May be {@code null}.
     * @param toVersion  Target version string.
     * @param view       Target view, or {@code null}.
     * @return A {@link PipelineResult} — never {@code null}.
     */
    public PipelineResult convert(String fpmlXml, ConversionDeltaProfile profile,
                                  String toVersion, String view) {
        Document doc = parseXml(fpmlXml);
        if (doc == null)
            return PipelineResult.failure(null, null, "Failed to parse FpML XML input");

        return new FpMLConversionPipeline.Builder(toVersion)
                .withProfile(profile)
                .validateInboundSchema(false)
                .validateOutboundSchema(false)
                .build()
                .convert(doc);
    }

    // =========================================================================
    // Probe API
    // =========================================================================

    /**
     * Probes whether a conversion can complete without additional input.
     *
     * <p>The probe runs a real conversion attempt using a
     * {@link RecordingRuntimeValueProvider} in place of stdin or a user-supplied
     * map.  If the recording provider was never invoked (i.e. the pipeline never
     * needed a runtime-prompted value) the conversion result is returned
     * immediately as a complete probe.  If the provider was called, the
     * discovered required fields are returned and the caller must supply values
     * before re-submitting via
     * {@link #convert(String, String, String, Map)}.</p>
     *
     * <p>For 5.x→5.x conversions the probe always completes in one pass because
     * no structural helper values are required.  For 4.x FX conversions the probe
     * may return required fields if the delta profile lacks pre-configured
     * currency values.</p>
     *
     * @param fpmlXml    Well-formed FpML XML string.
     * @param toVersion  Target version string.
     * @param view       Target view, or {@code null}.
     * @return A {@link ProbeResult} — never {@code null}.
     */
    public ProbeResult probe(String fpmlXml, String toVersion, String view) {
        Document doc = parseXml(fpmlXml);
        if (doc == null)
            return ProbeResult.incomplete(Collections.singletonList(
                    new ProbeResult.RequiredField("_parseError",
                            "Failed to parse XML input", "", ProbeResult.RequiredField.FieldKind.RUNTIME_PROMPT)));

        RecordingRuntimeValueProvider recorder = new RecordingRuntimeValueProvider();

        // Run the conversion with the recording provider
        String sourceVersion = detectSourceVersion(doc);
        String sourceView    = (view != null) ? view : detectView(doc);

        // Resolve the default inbound rule set (AllRules catches mandatory-field business rules).
        // Touch AllRules to ensure the class is loaded and the RuleSet is registered.
        RuleSet allRules = resolveDefaultRuleSet();

        PipelineResult result = new FpMLConversionPipeline.Builder(toVersion)
                .withAutoProfile(sourceVersion, sourceView)
                .validateInboundSchema(true)          // XSD validation — errors surface in inboundErrors
                .validateOutboundSchema(false)
                .withInboundRules(allRules)            // business-rule validation on source doc
                .withRuntimeValueProvider(recorder)
                .build()
                .convert(doc);

        // Collect source-document validation errors (XSD + business rules)
        List<String> sourceErrors = result.getInboundErrors();

        if (!sourceErrors.isEmpty()) {
            // Source document has validation errors — report them separately so the
            // caller knows the document needs fixing, not just value-filling.
            List<ProbeResult.RequiredField> fields = new ArrayList<>(recorder.getRequiredFields());
            return ProbeResult.withSourceErrors(fields, sourceErrors);
        }

        if (recorder.wasInvoked()) {
            // Pipeline needed runtime values — return the recorded required fields
            return ProbeResult.incomplete(recorder.getRequiredFields());
        }

        // No runtime prompts and no source errors — conversion completed
        return ProbeResult.complete(result);
    }

    // =========================================================================
    // Validation API
    // =========================================================================

    /**
     * Validates an FpML XML string (XSD + business-rules).
     *
     * <p>No conversion is performed.  The document is parsed, its release is
     * detected, and validation is run via a pipeline configured for no-op
     * conversion (source == target).  Inbound validation errors are returned
     * in {@link PipelineResult#getInboundErrors()}.</p>
     *
     * @param fpmlXml  Well-formed FpML XML string.
     * @return A {@link PipelineResult} — never {@code null}.
     */
    public PipelineResult validate(String fpmlXml) {
        Document doc = parseXml(fpmlXml);
        if (doc == null)
            return PipelineResult.failure(null, null, "Failed to parse FpML XML input");

        Release source = Specification.releaseForDocument(doc);
        if (source == null)
            return PipelineResult.failure(null, null,
                    "Cannot determine source FpML release from document");

        RuleSet allRules = resolveDefaultRuleSet();

        // Target == source → pipeline skips conversion and returns after inbound validation
        return new FpMLConversionPipeline.Builder(source.getVersion())
                .validateInboundSchema(true)    // XSD validation against version-specific schema
                .validateOutboundSchema(false)
                .withInboundRules(allRules)     // business-rule validation
                .failOnInboundErrors(false)     // always collect all errors, don't short-circuit
                .build()
                .convert(doc);
    }

    // =========================================================================
    // 8-step flow API
    // =========================================================================

    /**
     * Executes the full 8-step FpML conversion flow.
     *
     * <ol>
     *   <li>Identify source version (use {@code sourceVersion} if provided,
     *       else auto-detect from the document namespace).</li>
     *   <li>Inbound XSD schema validation (soft-fail — errors are reported but
     *       do not abort the flow).</li>
     *   <li>Build per-hop delta info from the profile chain for
     *       {@code fromVersion → toVersion}.</li>
     *   <li>Apply the profile chain for enrichment (autoProfile path).</li>
     *   <li>If {@code autoProfile=false}, probe for missing values; if
     *       {@code userValues} is supplied use them; else return
     *       {@link ConversionFlowResult.Status#NEEDS_INPUT}.</li>
     *   <li>Outbound XSD schema validation.</li>
     *   <li>Outbound business-rule validation (AllRules).</li>
     *   <li>Return {@link ConversionFlowResult} with all diagnostic data.</li>
     * </ol>
     *
     * <p>The status field of the returned result conveys the primary outcome:</p>
     * <ul>
     *   <li>{@link ConversionFlowResult.Status#COMPLETE} — all steps passed.</li>
     *   <li>{@link ConversionFlowResult.Status#NEEDS_INPUT} — caller must supply
     *       the values in {@link ConversionFlowResult#getMissingValues()} and
     *       re-invoke with {@code autoProfile=false, userValues=…}.</li>
     *   <li>{@link ConversionFlowResult.Status#CONVERSION_FAILED} — structural
     *       conversion error (no target document produced).</li>
     *   <li>{@link ConversionFlowResult.Status#TARGET_SCHEMA_ERROR} — target XSD
     *       issues (document produced but may be schema-invalid).</li>
     *   <li>{@link ConversionFlowResult.Status#TARGET_RULE_ERROR} — target
     *       business-rule violations.</li>
     * </ul>
     *
     * @param fpmlXml       Source FpML XML string (well-formed).
     * @param sourceVersion Explicit source version (e.g. {@code "5-12"}), or
     *                      {@code null} to auto-detect.
     * @param toVersion     Target version (e.g. {@code "5-13"}).
     * @param view          FpML view ({@code "confirmation"}), or {@code null}
     *                      to auto-detect from the source document.
     * @param autoProfile   {@code true} = use registry profiles for enrichment;
     *                      {@code false} = probe and prompt for missing values.
     * @param userValues    Caller-supplied field values (closes the NEEDS_INPUT
     *                      loop); may be {@code null} or empty.
     * @return A {@link ConversionFlowResult} — never {@code null}.
     */
    public ConversionFlowResult executeFlow(
            String fpmlXml,
            String sourceVersion,
            String toVersion,
            String view,
            boolean autoProfile,
            Map<String, String> userValues) {

        // --- Step 1: Parse ---------------------------------------------------
        Document doc = parseXml(fpmlXml);
        if (doc == null)
            return ConversionFlowResult.failed(
                    ConversionFlowResult.Status.CONVERSION_FAILED,
                    "Failed to parse FpML XML input",
                    sourceVersion != null ? sourceVersion : "unknown",
                    toVersion,
                    Collections.<String>emptyList(),
                    Collections.<ConversionFlowResult.HopDeltaInfo>emptyList());

        // Detect source version / view
        Release detectedRelease  = Specification.releaseForDocument(doc);
        String  detectedVersion  = (detectedRelease != null)
                ? detectedRelease.getVersion() : "unknown";
        String  effectiveVersion = (sourceVersion != null) ? sourceVersion : detectedVersion;
        String  effectiveView    = (view != null) ? view : detectView(doc);

        // --- Step 2: Inbound XSD schema validation (soft-fail) ---------------
        List<String> sourceSchemaErrors = Collections.emptyList();
        if (detectedRelease != null) {
            PipelineResult inboundResult = new FpMLConversionPipeline.Builder(detectedVersion)
                    .validateInboundSchema(true)
                    .validateOutboundSchema(false)
                    .failOnInboundErrors(false)
                    .build()
                    .convert(doc);
            sourceSchemaErrors = inboundResult.getInboundErrors();
        }

        // --- Steps 3+4: Build profile chain + delta info ----------------------
        List<ConversionDeltaProfile> profileChain =
                ConversionDeltaProfileRegistry.buildProfileChain(
                        effectiveVersion, toVersion, effectiveView);
        List<ConversionFlowResult.HopDeltaInfo> deltaChain =
                buildDeltaChain(profileChain);

        // --- Steps 5+6+7: Conversion + outbound validation -------------------
        RuleSet allRules = resolveDefaultRuleSet();
        PipelineResult conversionResult;

        if (autoProfile) {
            // 5a: run with auto profile chain, collect outbound validation
            conversionResult = new FpMLConversionPipeline.Builder(toVersion)
                    .withAutoProfileChain(effectiveVersion, effectiveView)
                    .validateInboundSchema(false)
                    .validateOutboundSchema(true)
                    .failOnOutboundErrors(false)
                    .withOutboundRules(allRules)
                    .build()
                    .convert(doc);
        } else {
            // 5b: first pass with recording provider to discover missing values
            RecordingRuntimeValueProvider recorder = new RecordingRuntimeValueProvider();
            new FpMLConversionPipeline.Builder(toVersion)
                    .withAutoProfileChain(effectiveVersion, effectiveView)
                    .withRuntimeValueProvider(recorder)
                    .validateInboundSchema(false)
                    .validateOutboundSchema(false)
                    .build()
                    .convert(doc);

            if (recorder.wasInvoked() && (userValues == null || userValues.isEmpty())) {
                // Missing values — return NEEDS_INPUT
                return ConversionFlowResult.needsInput(
                        effectiveVersion, toVersion,
                        sourceSchemaErrors, deltaChain,
                        recorder.getRequiredFields());
            }

            // Second pass: use user-supplied values (or no provider if no prompts needed)
            RuntimeValueProvider provider = (userValues != null && !userValues.isEmpty())
                    ? new MapRuntimeValueProvider(userValues) : null;
            FpMLConversionPipeline.Builder builder =
                    new FpMLConversionPipeline.Builder(toVersion)
                            .withAutoProfileChain(effectiveVersion, effectiveView)
                            .validateInboundSchema(false)
                            .validateOutboundSchema(true)
                            .failOnOutboundErrors(false)
                            .withOutboundRules(allRules);
            if (provider != null) builder.withRuntimeValueProvider(provider);
            conversionResult = builder.build().convert(doc);
        }

        // Structural failure (no converted document produced)
        if (!conversionResult.isSuccess()) {
            String issue = conversionResult.getErrors().isEmpty()
                    ? "Conversion failed"
                    : String.join("; ", conversionResult.getErrors());
            return ConversionFlowResult.failed(
                    ConversionFlowResult.Status.CONVERSION_FAILED,
                    issue,
                    effectiveVersion, toVersion,
                    sourceSchemaErrors, deltaChain);
        }

        // --- Steps 6+7: Parse outbound errors --------------------------------
        // The pipeline merges XSD errors (prefixed "[XSD") and rule errors.
        List<String> targetSchemaErrors = new ArrayList<>();
        List<String> targetRuleErrors   = new ArrayList<>();
        for (String e : conversionResult.getOutboundErrors()) {
            if (e.startsWith("[XSD")) targetSchemaErrors.add(e);
            else                      targetRuleErrors.add(e);
        }

        // --- Step 8: Build result --------------------------------------------
        List<String> droppedFields = conversionResult.getDroppedFields();

        ConversionFlowResult.Status status = ConversionFlowResult.Status.COMPLETE;
        if (!targetSchemaErrors.isEmpty())
            status = ConversionFlowResult.Status.TARGET_SCHEMA_ERROR;
        else if (!targetRuleErrors.isEmpty())
            status = ConversionFlowResult.Status.TARGET_RULE_ERROR;

        return ConversionFlowResult.complete(
                status,
                effectiveVersion, toVersion,
                sourceSchemaErrors, deltaChain,
                droppedFields,
                targetSchemaErrors, targetRuleErrors,
                conversionResult.getDocument());
    }

    // =========================================================================
    // Registry access
    // =========================================================================

    /**
     * Lists all delta profiles discovered by {@link ConversionDeltaProfileRegistry}.
     *
     * @return Unmodifiable snapshot of all loaded profiles.
     */
    public List<ConversionDeltaProfile> listProfiles() {
        return ConversionDeltaProfileRegistry.allProfiles();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static final Logger LOG =
            Logger.getLogger(FpMLConversionService.class.getName());

    /** Parses an XML string into a DOM Document using the toolkit's non-validating parser. */
    private static Document parseXml(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            return XmlUtility.nonValidatingParse(xml);
        } catch (Exception e) {
            LOG.warning("Failed to parse FpML XML: " + e.getMessage());
            return null;
        }
    }

    /**
     * Detects the source version string from the document's detected release.
     * Falls back to {@code "unknown"} if the release cannot be determined.
     */
    private static String detectSourceVersion(Document doc) {
        Release source = Specification.releaseForDocument(doc);
        return (source != null) ? source.getVersion() : "unknown";
    }

    /**
     * Detects the FpML view from the document's root namespace URI.
     *
     * <p>For FpML 5.x the namespace encodes the view, e.g.
     * {@code http://www.fpml.org/FpML-5/confirmation} → {@code "confirmation"}.
     * Returns {@code null} for FpML 4.x / DTD-based documents.</p>
     */
    private static String detectView(Document doc) {
        if (doc == null) return null;
        Element root = doc.getDocumentElement();
        if (root == null) return null;
        String ns = root.getNamespaceURI();
        if (ns == null) return null;
        int slash = ns.lastIndexOf('/');
        if (slash >= 0 && slash < ns.length() - 1)
            return ns.substring(slash + 1).toLowerCase();
        return null;
    }

    /**
     * Returns the {@code AllRules} RuleSet for inbound validation, or {@code null}
     * if the rule set cannot be resolved (e.g. validation classes not bootstrapped).
     * Touching {@link AllRules} forces class-loading which registers all sub-rule-sets.
     */
    private static RuleSet resolveDefaultRuleSet() {
        try {
            // Touch AllRules to ensure the class is loaded and rules are registered
            Class.forName("com.handcoded.fpml.validation.AllRules");
            return RuleSet.forName("AllRules");
        } catch (ClassNotFoundException | RuntimeException e) {
            LOG.warning("AllRules not available for inbound validation: " + e.getMessage());
            return null;
        }
    }

    /** Converts an XML string with a custom RuntimeValueProvider. */
    private PipelineResult convert(String fpmlXml, String toVersion, String view,
                                   RuntimeValueProvider provider) {
        Document doc = parseXml(fpmlXml);
        if (doc == null)
            return PipelineResult.failure(null, null, "Failed to parse FpML XML input");

        String sourceVersion = detectSourceVersion(doc);
        String sourceView    = (view != null) ? view : detectView(doc);

        FpMLConversionPipeline.Builder builder = new FpMLConversionPipeline.Builder(toVersion)
                .withAutoProfile(sourceVersion, sourceView)
                .validateInboundSchema(false)
                .validateOutboundSchema(false);

        if (provider != null)
            builder.withRuntimeValueProvider(provider);

        return builder.build().convert(doc);
    }

    // =========================================================================
    // Private helpers for executeFlow
    // =========================================================================

    /**
     * Builds a list of {@link ConversionFlowResult.HopDeltaInfo} from a profile
     * chain.  Required fields are derived from {@code prompt-at-runtime} and
     * {@code schema-required} conditional defaults.  Modified fields come from
     * {@code SET_ATTRIBUTE} / {@code SET_TEXT} enrichment actions.
     */
    private static List<ConversionFlowResult.HopDeltaInfo> buildDeltaChain(
            List<ConversionDeltaProfile> profileChain) {
        if (profileChain.isEmpty()) return Collections.emptyList();

        List<ConversionFlowResult.HopDeltaInfo> result = new ArrayList<>();
        for (ConversionDeltaProfile p : profileChain) {
            List<String> required = new ArrayList<>();
            List<String> modified = new ArrayList<>();

            for (ConversionDeltaProfile.ConditionalDefault cd : p.getConditionalDefaults()) {
                if (cd.isPromptAtRuntime()
                        || "schema-required".equals(cd.getConstraintType())) {
                    String label = (cd.getNote() != null && !cd.getNote().isEmpty())
                            ? cd.getNote() : cd.getXpath();
                    required.add(label);
                }
            }
            for (ConversionDeltaProfile.EnrichAction a : p.getEnrichActions()) {
                modified.add(a.toString());
            }

            result.add(new ConversionFlowResult.HopDeltaInfo(
                    p.getFromVersion(), p.getToVersion(),
                    p.getFromView(), required,
                    Collections.<String>emptyList(), modified));
        }
        return result;
    }

    // =========================================================================
    // RecordingRuntimeValueProvider — private inner class
    // =========================================================================

    /**
     * A {@link RuntimeValueProvider} that records every invocation instead of
     * prompting or returning a value.  Used by {@link #probe} to discover what
     * fields the pipeline needs at runtime.
     *
     * <p>Every {@link #provideValue} call appends a {@link ProbeResult.RequiredField}
     * to the recorded list and returns {@code null} (causing the pipeline to skip
     * the injection for the current run, which is acceptable — the caller will
     * supply real values on the second pass).</p>
     */
    private static final class RecordingRuntimeValueProvider implements RuntimeValueProvider {

        private final List<ProbeResult.RequiredField> fields = new ArrayList<>();

        @Override
        public String provideValue(String xpath, String constraintType, String note,
                                   String locationPath) {
            // Use the concrete location path as the field key when available so
            // that the probe result distinguishes between multiple nodes matched
            // by the same template XPath (e.g. two <partyId> elements).
            String effectiveXpath = (locationPath != null && !locationPath.isEmpty())
                    ? locationPath : xpath;
            String name  = extractShortName(effectiveXpath);
            String label = buildLabel(name, constraintType, note);
            fields.add(new ProbeResult.RequiredField(
                    effectiveXpath, label, "",
                    ProbeResult.RequiredField.FieldKind.RUNTIME_PROMPT));
            return null;   // do not inject — caller will supply on next pass
        }

        /**
         * Overrides the default to record structural helper fields with kind
         * {@link ProbeResult.RequiredField.FieldKind#HELPER_VALUE} so that the
         * probe response distinguishes them from {@code RUNTIME_PROMPT} fields.
         */
        @Override
        public String provideHelperValue(String key, String note) {
            String label = (note != null && !note.isEmpty()) ? note : key;
            fields.add(new ProbeResult.RequiredField(
                    key, label, "",
                    ProbeResult.RequiredField.FieldKind.HELPER_VALUE));
            return null;   // do not inject — caller will supply on next pass
        }

        boolean wasInvoked() { return !fields.isEmpty(); }

        List<ProbeResult.RequiredField> getRequiredFields() {
            return Collections.unmodifiableList(fields);
        }

        /** Extracts a short name from an XPath expression (last @attr or element). */
        private static String extractShortName(String xpath) {
            if (xpath == null) return "unknown";
            int atIdx = xpath.lastIndexOf('@');
            if (atIdx >= 0) return cleanIdentifier(xpath.substring(atIdx + 1));
            int slashIdx = xpath.lastIndexOf('/');
            if (slashIdx >= 0 && slashIdx < xpath.length() - 1)
                return cleanIdentifier(xpath.substring(slashIdx + 1));
            return cleanIdentifier(xpath);
        }

        private static String cleanIdentifier(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
                else break;
            }
            return sb.length() > 0 ? sb.toString() : "unknown";
        }

        private static String buildLabel(String name, String constraintType, String note) {
            if (note != null && !note.isEmpty()) return note;
            if (constraintType != null && !constraintType.isEmpty())
                return name + " (" + constraintType + ")";
            return name;
        }
    }
}
