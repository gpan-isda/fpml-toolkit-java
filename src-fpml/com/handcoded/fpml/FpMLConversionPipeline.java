// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import com.handcoded.meta.Conversion;
import com.handcoded.meta.ConversionException;
import com.handcoded.meta.Release;
import com.handcoded.meta.SchemaRelease;
import com.handcoded.meta.Specification;
import com.handcoded.validation.RuleSet;
import com.handcoded.validation.ValidationErrorHandler;
import com.handcoded.xml.SchemaSet;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;

import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates the conversion of an FpML document from its detected source
 * release to a named target version.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * // Build a reusable pipeline (no profile — pure namespace upgrade)
 * FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-13").build();
 *
 * // With a delta profile for helper values / enrichment / declared rule sets
 * ConversionDeltaProfile profile = ConversionDeltaProfile.load(new File("my-profile.xml"));
 * FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-13")
 *         .withProfile(profile)
 *         .failOnInboundErrors(true)
 *         .failOnOutboundErrors(true)
 *         .build();
 *
 * // Builder-supplied RuleSets override profile-declared names
 * FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-13")
 *         .withProfile(profile)
 *         .withInboundRules(RuleSet.forName("AllRules"))
 *         .withOutboundRules(RuleSet.forName("AllRules"))
 *         .build();
 *
 * // Convert a parsed document
 * PipelineResult result = pipeline.convert(document);
 * if (result.isSuccess()) {
 *     Document converted = result.getDocument();
 *     if (result.hasValidationErrors()) { ... }
 *     if (!result.getDroppedFields().isEmpty()) { ... }
 * }
 * }</pre>
 *
 * <h3>Validation resolution order</h3>
 * <ol>
 *   <li>Builder-supplied {@link RuleSet} (via {@link Builder#withInboundRules} /
 *       {@link Builder#withOutboundRules}) — highest precedence.</li>
 *   <li>Profile-declared rule set names (from {@code <rules>} in the delta profile XML),
 *       resolved via {@link RuleSet#forName(String)}.</li>
 *   <li>No validation — if neither is configured validation is silently skipped.</li>
 * </ol>
 *
 * <p>Instances are thread-safe once constructed (the {@code Builder} is not).</p>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    Builder
 * @see    PipelineResult
 * @see    ConversionDeltaProfile
 * @see    ValidationRuleLoader
 * @since  TFP 1.x
 */
public final class FpMLConversionPipeline {

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Fluent builder for {@link FpMLConversionPipeline}.
     */
    public static final class Builder {

        private final String              targetVersion;
        private       ConversionDeltaProfile profile;
        private       List<ConversionDeltaProfile> profileChain = Collections.emptyList();
        private       String              overrideSourceVersion;
        private       boolean             failFastOnNullRelease  = true;
        private       boolean             failOnInboundErrors    = false;
        private       boolean             failOnOutboundErrors   = false;
        private       boolean             validateInboundSchema  = true;
        private       boolean             validateOutboundSchema = true;
        private       RuleSet             inboundRules;
        private       RuleSet             outboundRules;
        private       RuntimeValueProvider runtimeValueProvider;

        /**
         * Starts building a pipeline that targets the given FpML version.
         *
         * @param targetVersion  Target version string, e.g. {@code "5-13"}.
         */
        public Builder(String targetVersion) {
            if (targetVersion == null || targetVersion.isEmpty())
                throw new IllegalArgumentException("targetVersion must not be blank");
            this.targetVersion = targetVersion;
        }

        /**
         * Attaches a delta profile.  The profile supplies helper values for structural
         * conversions, enrichment actions for post-processing, and optionally declares
         * inbound/outbound rule set names (overridden by {@link #withInboundRules} /
         * {@link #withOutboundRules} if also supplied).
         */
        public Builder withProfile(ConversionDeltaProfile profile) {
            this.profile = profile;
            return this;
        }

        /**
         * Looks up a matching profile automatically from the
         * {@link ConversionDeltaProfileRegistry}.  The registry scans the default
         * profile directory ({@code files-fpml/conversion-profiles}) on first use.
         *
         * <p>The {@code sourceVersion} and {@code view} parameters are used to
         * narrow the match.  If {@code view} is {@code null} the registry returns
         * the first profile whose {@code from} matches the source version and whose
         * target matches the configured {@link Builder#Builder(String) targetVersion},
         * regardless of view.</p>
         *
         * <p>If no matching profile is found this method is a no-op (the pipeline
         * runs without a profile).  Any profile previously set via
         * {@link #withProfile(ConversionDeltaProfile)} is replaced if a match is
         * found.</p>
         *
         * @param sourceVersion  Source version string, e.g. {@code "5-12"}.
         * @param view           FpML view (e.g. {@code "confirmation"}), or
         *                       {@code null} for any view.
         * @return {@code this}
         */
        public Builder withAutoProfile(String sourceVersion, String view) {
            ConversionDeltaProfile found =
                    ConversionDeltaProfileRegistry.profileFor(
                            sourceVersion, targetVersion, view);
            if (found != null) this.profile = found;
            return this;
        }

        /**
         * Overrides the auto-detected source release version.
         *
         * <p>When set, the pipeline falls back to this version string if
         * {@link Specification#releaseForDocument} cannot identify the source
         * release.  For well-formed FpML 4.x / 5.x documents auto-detection
         * works reliably; this override is useful for edge cases such as
         * partially-converted or namespace-less documents.</p>
         *
         * @param sourceVersion  Override source version string (e.g. {@code "5-12"}),
         *                       or {@code null} to rely on auto-detection only.
         * @return {@code this}
         */
        public Builder withSourceVersion(String sourceVersion) {
            this.overrideSourceVersion = sourceVersion;
            return this;
        }

        /**
         * Builds a multi-hop profile chain from {@code sourceVersion} to the
         * pipeline's {@link #Builder(String) targetVersion} and stores it for
         * sequential enrichment.
         *
         * <p>Internally calls
         * {@link ConversionDeltaProfileRegistry#buildProfileChain(String, String, String)}
         * and stores the result.  The <em>last</em> profile in the chain is also
         * set as the single {@link #withProfile profile} so that
         * {@link ConfigurableHelper} picks up any declared helper values (FX
         * currencies, etc.).</p>
         *
         * <p>If no profiles are found this method is a no-op (conversion proceeds
         * without enrichment).</p>
         *
         * @param sourceVersion  Source version string, e.g. {@code "5-11"}.
         * @param view           FpML view (e.g. {@code "confirmation"}), or
         *                       {@code null} for any view.
         * @return {@code this}
         */
        public Builder withAutoProfileChain(String sourceVersion, String view) {
            List<ConversionDeltaProfile> chain =
                    ConversionDeltaProfileRegistry.buildProfileChain(
                            sourceVersion, targetVersion, view);
            this.profileChain = chain;
            if (!chain.isEmpty())
                this.profile = chain.get(chain.size() - 1);
            return this;
        }

        /**
         * Supplies the {@link RuleSet} to validate the <em>source</em> document before
         * conversion.  Takes precedence over any rule set name declared in the profile.
         */
        public Builder withInboundRules(RuleSet rules) {
            this.inboundRules = rules;
            return this;
        }

        /**
         * Supplies the {@link RuleSet} to validate the <em>converted</em> document after
         * enrichment.  Takes precedence over any rule set name declared in the profile.
         */
        public Builder withOutboundRules(RuleSet rules) {
            this.outboundRules = rules;
            return this;
        }

        /**
         * Convenience: load additional rule files from {@code rulesDir} via
         * {@link ValidationRuleLoader#loadFrom(Path)} and merge them into the global
         * {@link RuleSet} registry so that profile-declared names resolve correctly.
         *
         * @param rulesDir  Path to a directory containing {@code business-rules.xml}-compatible
         *                  rule definition files.
         */
        public Builder withValidationRulesPath(Path rulesDir) {
            ValidationRuleLoader.loadFrom(rulesDir);
            return this;
        }

        /**
         * When {@code true}, a non-empty inbound error list causes
         * {@link FpMLConversionPipeline#convert(Document)} to return a failure result
         * immediately, without proceeding to conversion.  Default {@code false}.
         */
        public Builder failOnInboundErrors(boolean value) {
            this.failOnInboundErrors = value;
            return this;
        }

        /**
         * When {@code true}, a non-empty outbound error list causes
         * {@link FpMLConversionPipeline#convert(Document)} to return a failure result.
         * Default {@code false}.
         */
        public Builder failOnOutboundErrors(boolean value) {
            this.failOnOutboundErrors = value;
            return this;
        }

        /**
         * Controls whether the source document is XSD-validated against the
         * version-specific schema before conversion.  Default {@code true}.
         */
        public Builder validateInboundSchema(boolean value) {
            this.validateInboundSchema = value;
            return this;
        }

        /**
         * Controls whether the converted document is XSD-validated against the
         * target version-specific schema after enrichment.  Default {@code true}.
         */
        public Builder validateOutboundSchema(boolean value) {
            this.validateOutboundSchema = value;
            return this;
        }

        /**
         * Controls whether {@link FpMLConversionPipeline#convert(Document)} returns a
         * failure result immediately when the source release cannot be detected.
         * Default {@code true}.
         */
        public Builder failFastOnUnknownSource(boolean value) {
            this.failFastOnNullRelease = value;
            return this;
        }

        /**
         * Attaches a {@link RuntimeValueProvider} that is called at conversion
         * time when a {@code prompt-at-runtime="true"} profile entry encounters a
         * missing mandatory node.
         *
         * <p>The most common implementation is {@link ConsoleRuntimeValueProvider}
         * for interactive command-line conversions.  Pass {@code null} (the default)
         * to silently skip all prompt-at-runtime entries.</p>
         *
         * @param provider  The provider to use, or {@code null} to disable.
         * @return {@code this}
         */
        public Builder withRuntimeValueProvider(RuntimeValueProvider provider) {
            this.runtimeValueProvider = provider;
            return this;
        }

        /** @return A configured {@link FpMLConversionPipeline}. */
        public FpMLConversionPipeline build() {
            return new FpMLConversionPipeline(targetVersion, profile, profileChain,
                    overrideSourceVersion,
                    failFastOnNullRelease, failOnInboundErrors, failOnOutboundErrors,
                    validateInboundSchema, validateOutboundSchema,
                    inboundRules, outboundRules, runtimeValueProvider);
        }
    }

    // =========================================================================
    // Conversion entry point
    // =========================================================================

    /**
     * Converts the given document to the pipeline's target version.
     *
     * <p>Execution order:</p>
     * <ol>
     *   <li>Detect source {@link Release}.</li>
     *   <li>Resolve target {@link Release} compatible with the source's view.</li>
     *   <li>Optionally validate the source document (inbound rule set).</li>
     *   <li>Find and apply the conversion path, collecting dropped fields.</li>
     *   <li>Run {@link PostConversionEnricher}.</li>
     *   <li>Optionally validate the converted document (outbound rule set).</li>
     * </ol>
     *
     * @param  document  The source FpML document.  Must not be {@code null}.
     * @return A {@link PipelineResult} — never {@code null}.
     */
    public PipelineResult convert(Document document) {
        if (document == null)
            return PipelineResult.failure(null, null, "Source document is null");

        // 1. Detect source release (honouring any override version)
        Release source = resolveSourceRelease(document, overrideSourceVersion);
        if (source == null && failFastOnNullRelease)
            return PipelineResult.failure(null, null,
                    "Cannot determine source FpML release from document");

        // 2. Resolve target release compatible with source view
        Release target = Releases.compatibleRelease(document, targetVersion);
        if (target == null)
            return PipelineResult.failure(source, null,
                    "No compatible target release found for version '" + targetVersion
                    + "' given source release " + (source != null ? source.getVersion() : "?"));

        // 3. Inbound XSD schema validation (version-specific)
        List<String> inboundSchemaErrors = Collections.emptyList();
        if (validateInboundSchema && source instanceof SchemaRelease) {
            inboundSchemaErrors = xsdValidate((SchemaRelease) source, document);
            if (failOnInboundErrors && !inboundSchemaErrors.isEmpty())
                return PipelineResult.full(false, source, target, null,
                        Collections.singletonList("Inbound XSD schema validation failed"),
                        inboundSchemaErrors,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList());
        }

        // 3b. Inbound business-rules validation (advisory or blocking)
        List<String> inboundErrors = Collections.emptyList();
        RuleSet inbound = resolveInboundRules();
        if (inbound != null) {
            CollectingErrorHandler handler = new CollectingErrorHandler();
            inbound.validate(document, handler);
            // Merge schema errors + business-rule errors into single inbound list
            List<String> combined = new ArrayList<>(inboundSchemaErrors);
            combined.addAll(handler.getMessages());
            inboundErrors = Collections.unmodifiableList(combined);
            if (failOnInboundErrors && !handler.getMessages().isEmpty())
                return PipelineResult.full(false, source, target, null,
                        Collections.singletonList("Inbound validation failed"),
                        inboundErrors,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList());
        } else {
            inboundErrors = inboundSchemaErrors;
        }

        // Short-circuit: already at the target
        if (source != null && source.equals(target))
            return PipelineResult.full(true, source, target, document,
                    Collections.<String>emptyList(),
                    inboundErrors,
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList());

        // 4. Find conversion path
        Conversion conversion = (source != null)
                ? Conversion.conversionFor(source, target)
                : null;
        if (conversion == null)
            return PipelineResult.full(false, source, target, null,
                    Collections.singletonList("No conversion path found from "
                            + (source != null ? source.getVersion() : "?")
                            + " to " + target.getVersion()),
                    inboundErrors,
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList());

        // 4b. Apply conversion — use DroppingHelper to collect dropped fields
        Conversions.SimpleDroppedFieldCollector collector =
                new Conversions.SimpleDroppedFieldCollector();
        ConfigurableHelper helper = new ConfigurableHelper(profile, collector, runtimeValueProvider);
        Document converted;
        try {
            converted = conversion.convert(document, helper);
        } catch (ConversionException e) {
            return PipelineResult.full(false, source, target, null,
                    Collections.singletonList("Conversion failed: " + e.getMessage()),
                    inboundErrors, Collections.<String>emptyList(),
                    Collections.<String>emptyList());
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            String msg = (cause instanceof ConversionException)
                    ? cause.getMessage() : e.getMessage();
            return PipelineResult.full(false, source, target, null,
                    Collections.singletonList("Conversion error: " + msg),
                    inboundErrors, Collections.<String>emptyList(),
                    Collections.<String>emptyList());
        }

        // 5. Post-conversion enrichment — apply each profile in chain order
        for (ConversionDeltaProfile p : effectiveProfileChain()) {
            PostConversionEnricher.enrich(converted, p);
            PostConversionEnricher.applyConditionalDefaults(converted, p,
                    runtimeValueProvider);
        }

        // 6. Outbound XSD schema validation (version-specific, against target schema)
        List<String> outboundSchemaErrors = Collections.emptyList();
        if (validateOutboundSchema && target instanceof SchemaRelease) {
            outboundSchemaErrors = xsdValidate((SchemaRelease) target, converted);
            if (failOnOutboundErrors && !outboundSchemaErrors.isEmpty())
                return PipelineResult.full(false, source, target, converted,
                        Collections.singletonList("Outbound XSD schema validation failed"),
                        inboundErrors, outboundSchemaErrors,
                        collector.getDroppedFields());
        }

        // 6b. Outbound business-rules validation
        List<String> outboundErrors = Collections.emptyList();
        RuleSet outbound = resolveOutboundRules();
        if (outbound != null) {
            CollectingErrorHandler handler = new CollectingErrorHandler();
            outbound.validate(converted, handler);
            List<String> combined = new ArrayList<>(outboundSchemaErrors);
            combined.addAll(handler.getMessages());
            outboundErrors = Collections.unmodifiableList(combined);
            if (failOnOutboundErrors && !handler.getMessages().isEmpty())
                return PipelineResult.full(false, source, target, converted,
                        Collections.singletonList("Outbound validation failed"),
                        inboundErrors, outboundErrors,
                        collector.getDroppedFields());
        } else {
            outboundErrors = outboundSchemaErrors;
        }

        return PipelineResult.full(true, source, target, converted,
                Collections.<String>emptyList(),
                inboundErrors, outboundErrors,
                collector.getDroppedFields());
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** @return The target version string supplied at construction time. */
    public String getTargetVersion() { return targetVersion; }

    /** @return The delta profile, or {@code null} if none. */
    public ConversionDeltaProfile getProfile() { return profile; }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Resolves the source {@link Release} from the document, falling back to
     * {@code overrideVersion} if auto-detection fails.
     *
     * @param doc             The source document.
     * @param overrideVersion Optional override version string; {@code null} means
     *                        rely entirely on auto-detection.
     * @return The best-matching release, or {@code null} if neither approach works.
     */
    private static Release resolveSourceRelease(Document doc, String overrideVersion) {
        Release autoDetected = Specification.releaseForDocument(doc);
        if (overrideVersion == null || autoDetected != null) return autoDetected;

        // Auto-detection failed; try lookup by override version
        Specification fpml = Specification.forName("FpML");
        if (fpml != null) {
            Release override = fpml.getReleaseForVersion(overrideVersion);
            if (override != null) return override;
        }
        return null;
    }

    /**
     * Returns the effective profile chain for enrichment:
     * <ul>
     *   <li>If {@code profileChain} is non-empty, return it.</li>
     *   <li>Else if {@code profile} is non-null, return a singleton list.</li>
     *   <li>Otherwise return an empty list (no enrichment).</li>
     * </ul>
     */
    private List<ConversionDeltaProfile> effectiveProfileChain() {
        if (!profileChain.isEmpty()) return profileChain;
        if (profile != null)         return Collections.singletonList(profile);
        return Collections.emptyList();
    }

    /**
     * XSD-validates {@code doc} against the version-specific compiled schema for
     * {@code release}.  Returns an unmodifiable list of error messages (empty if valid).
     */
    private static List<String> xsdValidate(SchemaRelease release, Document doc) {
        SchemaSet schemaSet = new SchemaSet();
        schemaSet.add(release);
        Schema schema;
        try {
            schema = schemaSet.getSchema();
        } catch (NullPointerException npe) {
            // Catalog not bootstrapped (XmlUtility.setDefaultCatalog not called yet).
            // XSD validation is skipped — business-rule validation still runs.
            LOG.fine("XSD validation skipped: catalog not initialised (" + npe.getMessage() + ")");
            return Collections.emptyList();
        }
        if (schema == null) return Collections.emptyList();

        final List<String> errors = new ArrayList<>();
        try {
            Validator validator = schema.newValidator();
            validator.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override public void warning(SAXParseException e) { /* ignore warnings */ }
                @Override public void error(SAXParseException e) {
                    errors.add("[XSD] " + e.getMessage()
                            + (e.getLineNumber() > 0 ? " (line " + e.getLineNumber() + ")" : ""));
                }
                @Override public void fatalError(SAXParseException e) {
                    errors.add("[XSD-FATAL] " + e.getMessage()
                            + (e.getLineNumber() > 0 ? " (line " + e.getLineNumber() + ")" : ""));
                }
            });
            validator.validate(new DOMSource(doc));
        } catch (org.xml.sax.SAXException | IOException ex) {
            errors.add("[XSD] Validation error: " + ex.getMessage());
        }
        return Collections.unmodifiableList(errors);
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(FpMLConversionPipeline.class.getName());

    /**
     * Resolves the inbound {@link RuleSet} to use: builder-supplied takes priority,
     * then profile-declared name, then {@code null} (skip validation).
     */
    private RuleSet resolveInboundRules() {
        if (inboundRules != null) return inboundRules;
        if (profile != null && profile.getInboundRuleSetName() != null)
            return RuleSet.forName(profile.getInboundRuleSetName());
        return null;
    }

    /** Resolves the outbound {@link RuleSet}: same priority order as inbound. */
    private RuleSet resolveOutboundRules() {
        if (outboundRules != null) return outboundRules;
        if (profile != null && profile.getOutboundRuleSetName() != null)
            return RuleSet.forName(profile.getOutboundRuleSetName());
        return null;
    }

    /**
     * A {@link ValidationErrorHandler} that accumulates error messages into a list
     * for later retrieval.  One instance is created per validation call so there is
     * no shared mutable state between pipeline invocations.
     */
    private static final class CollectingErrorHandler implements ValidationErrorHandler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void error(String code, Node context, String description,
                          String ruleName, String additionalData) {
            StringBuilder sb = new StringBuilder();
            if (ruleName    != null) sb.append("[").append(ruleName).append("] ");
            if (description != null) sb.append(description);
            if (code        != null) sb.append(" (code=").append(code).append(")");
            if (additionalData != null) sb.append(" [").append(additionalData).append("]");
            messages.add(sb.toString());
        }

        List<String> getMessages() { return Collections.unmodifiableList(messages); }
    }

    // =========================================================================
    // Private constructor
    // =========================================================================

    private FpMLConversionPipeline(String targetVersion,
                                   ConversionDeltaProfile profile,
                                   List<ConversionDeltaProfile> profileChain,
                                   String overrideSourceVersion,
                                   boolean failFastOnNullRelease,
                                   boolean failOnInboundErrors,
                                   boolean failOnOutboundErrors,
                                   boolean validateInboundSchema,
                                   boolean validateOutboundSchema,
                                   RuleSet inboundRules,
                                   RuleSet outboundRules,
                                   RuntimeValueProvider runtimeValueProvider) {
        this.targetVersion          = targetVersion;
        this.profile                = profile;
        this.profileChain           = (profileChain != null) ? profileChain : Collections.<ConversionDeltaProfile>emptyList();
        this.overrideSourceVersion  = overrideSourceVersion;
        this.failFastOnNullRelease  = failFastOnNullRelease;
        this.failOnInboundErrors    = failOnInboundErrors;
        this.failOnOutboundErrors   = failOnOutboundErrors;
        this.validateInboundSchema  = validateInboundSchema;
        this.validateOutboundSchema = validateOutboundSchema;
        this.inboundRules           = inboundRules;
        this.outboundRules          = outboundRules;
        this.runtimeValueProvider   = runtimeValueProvider;
    }

    private final String                         targetVersion;
    private final ConversionDeltaProfile         profile;
    private final List<ConversionDeltaProfile>   profileChain;
    private final String                         overrideSourceVersion;
    private final boolean                        failFastOnNullRelease;
    private final boolean                        failOnInboundErrors;
    private final boolean                        failOnOutboundErrors;
    private final boolean                        validateInboundSchema;
    private final boolean                        validateOutboundSchema;
    private final RuleSet                        inboundRules;
    private final RuleSet                        outboundRules;
    private final RuntimeValueProvider           runtimeValueProvider;
}
