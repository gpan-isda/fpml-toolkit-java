// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import com.handcoded.meta.Conversion;
import com.handcoded.meta.ConversionException;
import com.handcoded.meta.Release;
import com.handcoded.meta.Specification;
import com.handcoded.validation.RuleSet;
import com.handcoded.validation.ValidationErrorHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

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
        private       boolean             failFastOnNullRelease  = true;
        private       boolean             failOnInboundErrors    = false;
        private       boolean             failOnOutboundErrors   = false;
        private       RuleSet             inboundRules;
        private       RuleSet             outboundRules;

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
         * Controls whether {@link FpMLConversionPipeline#convert(Document)} returns a
         * failure result immediately when the source release cannot be detected.
         * Default {@code true}.
         */
        public Builder failFastOnUnknownSource(boolean value) {
            this.failFastOnNullRelease = value;
            return this;
        }

        /** @return A configured {@link FpMLConversionPipeline}. */
        public FpMLConversionPipeline build() {
            return new FpMLConversionPipeline(targetVersion, profile,
                    failFastOnNullRelease, failOnInboundErrors, failOnOutboundErrors,
                    inboundRules, outboundRules);
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

        // 1. Detect source release
        Release source = Specification.releaseForDocument(document);
        if (source == null && failFastOnNullRelease)
            return PipelineResult.failure(null, null,
                    "Cannot determine source FpML release from document");

        // 2. Resolve target release compatible with source view
        Release target = Releases.compatibleRelease(document, targetVersion);
        if (target == null)
            return PipelineResult.failure(source, null,
                    "No compatible target release found for version '" + targetVersion
                    + "' given source release " + (source != null ? source.getVersion() : "?"));

        // 3. Inbound validation (advisory or blocking)
        List<String> inboundErrors = Collections.emptyList();
        RuleSet inbound = resolveInboundRules();
        if (inbound != null) {
            CollectingErrorHandler handler = new CollectingErrorHandler();
            inbound.validate(document, handler);
            inboundErrors = handler.getMessages();
            if (failOnInboundErrors && !inboundErrors.isEmpty())
                return PipelineResult.full(false, source, target, null,
                        Collections.singletonList("Inbound validation failed"),
                        inboundErrors,
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList());
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
        ConfigurableHelper helper = new ConfigurableHelper(profile, collector);
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

        // 5. Post-conversion enrichment
        PostConversionEnricher.enrich(converted, profile);

        // 6. Outbound validation
        List<String> outboundErrors = Collections.emptyList();
        RuleSet outbound = resolveOutboundRules();
        if (outbound != null) {
            CollectingErrorHandler handler = new CollectingErrorHandler();
            outbound.validate(converted, handler);
            outboundErrors = handler.getMessages();
            if (failOnOutboundErrors && !outboundErrors.isEmpty())
                return PipelineResult.full(false, source, target, converted,
                        Collections.singletonList("Outbound validation failed"),
                        inboundErrors, outboundErrors,
                        collector.getDroppedFields());
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
                                   boolean failFastOnNullRelease,
                                   boolean failOnInboundErrors,
                                   boolean failOnOutboundErrors,
                                   RuleSet inboundRules,
                                   RuleSet outboundRules) {
        this.targetVersion         = targetVersion;
        this.profile               = profile;
        this.failFastOnNullRelease = failFastOnNullRelease;
        this.failOnInboundErrors   = failOnInboundErrors;
        this.failOnOutboundErrors  = failOnOutboundErrors;
        this.inboundRules          = inboundRules;
        this.outboundRules         = outboundRules;
    }

    private final String                 targetVersion;
    private final ConversionDeltaProfile profile;
    private final boolean                failFastOnNullRelease;
    private final boolean                failOnInboundErrors;
    private final boolean                failOnOutboundErrors;
    private final RuleSet                inboundRules;
    private final RuleSet                outboundRules;
}
