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
import org.w3c.dom.Document;

/**
 * Orchestrates the conversion of an FpML document from its detected source
 * release to a named target version.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * // Build a reusable pipeline (no profile — pure namespace upgrade)
 * FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-13").build();
 *
 * // With a delta profile for helper values / post-conversion enrichment
 * ConversionDeltaProfile profile = ConversionDeltaProfile.load(new File("my-profile.xml"));
 * FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-13")
 *         .withProfile(profile)
 *         .build();
 *
 * // Convert a parsed document
 * PipelineResult result = pipeline.convert(document);
 * if (result.isSuccess()) {
 *     Document converted = result.getDocument();
 * } else {
 *     System.err.println(result.getErrors());
 * }
 * }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>The pipeline detects the source release from the document's namespace
 *       and {@code fpmlVersion} / {@code version} attribute.</li>
 *   <li>The target release is resolved by matching the requested target version
 *       and the source document's view (confirmation, reporting, …) using
 *       {@link Releases#compatibleRelease(Document, String)}.</li>
 *   <li>The conversion path is discovered via the framework's
 *       {@link Conversion#conversionFor(Release, Release)} depth-first search,
 *       which traverses the registered {@link com.handcoded.meta.DirectConversion}
 *       graph (including all the pass-through conversions registered by
 *       {@link Conversions}).</li>
 *   <li>After the structural conversion, any enrichment actions from the delta
 *       profile are applied by {@link PostConversionEnricher}.</li>
 * </ul>
 *
 * <p>Instances are thread-safe once constructed (the {@code Builder} is not).</p>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    Builder
 * @see    PipelineResult
 * @see    ConversionDeltaProfile
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

        private final String             targetVersion;
        private       ConversionDeltaProfile profile;
        private       boolean            failFastOnNullRelease = true;

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
         * Attaches a delta profile.  The profile supplies helper values for
         * structural conversions and enrichment actions for post-processing.
         *
         * @param profile  The profile, or {@code null} to use defaults.
         * @return This builder (fluent).
         */
        public Builder withProfile(ConversionDeltaProfile profile) {
            this.profile = profile;
            return this;
        }

        /**
         * Controls whether {@link #convert(Document)} returns a failure result
         * immediately when the source release cannot be detected.  Default
         * {@code true}.
         *
         * @param value  {@code true} to fail fast; {@code false} to attempt
         *               conversion anyway.
         * @return This builder (fluent).
         */
        public Builder failFastOnUnknownSource(boolean value) {
            this.failFastOnNullRelease = value;
            return this;
        }

        /** @return A configured {@link FpMLConversionPipeline}. */
        public FpMLConversionPipeline build() {
            return new FpMLConversionPipeline(targetVersion, profile, failFastOnNullRelease);
        }
    }

    // =========================================================================
    // Conversion entry point
    // =========================================================================

    /**
     * Converts the given document to the pipeline's target version.
     *
     * <p>The method:</p>
     * <ol>
     *   <li>Detects the source {@link Release} from the document.</li>
     *   <li>Resolves the target {@link Release} compatible with the source's view.</li>
     *   <li>Looks up (or traverses) the conversion path in the framework registry.</li>
     *   <li>Applies the conversion with a {@link ConfigurableHelper} backed by the
     *       delta profile (if any).</li>
     *   <li>Runs {@link PostConversionEnricher} to apply any configured enrichment.</li>
     * </ol>
     *
     * @param  document  The source FpML document to convert.  Must not be {@code null}.
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

        // Short-circuit: already at the target
        if (source != null && source.equals(target))
            return PipelineResult.success(source, target, document);

        // 3. Find conversion path
        Conversion conversion = (source != null)
                ? Conversion.conversionFor(source, target)
                : null;

        if (conversion == null)
            return PipelineResult.failure(source, target,
                    "No conversion path found from "
                    + (source != null ? source.getVersion() : "?")
                    + " to " + target.getVersion());

        // 4. Apply conversion
        ConfigurableHelper helper = new ConfigurableHelper(profile);
        Document converted;
        try {
            converted = conversion.convert(document, helper);
        } catch (ConversionException e) {
            return PipelineResult.failure(source, target,
                    "Conversion failed: " + e.getMessage());
        } catch (RuntimeException e) {
            // DirectConversion wraps ConversionException in RuntimeException in some paths
            Throwable cause = e.getCause();
            String msg = (cause instanceof ConversionException)
                    ? cause.getMessage() : e.getMessage();
            return PipelineResult.failure(source, target, "Conversion error: " + msg);
        }

        // 5. Post-conversion enrichment
        PostConversionEnricher.enrich(converted, profile);

        return PipelineResult.success(source, target, converted);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** @return The target version string supplied at construction time. */
    public String getTargetVersion() { return targetVersion; }

    /**
     * @return The delta profile attached to this pipeline, or {@code null} if
     *         none was provided.
     */
    public ConversionDeltaProfile getProfile() { return profile; }

    // =========================================================================
    // Private constructor
    // =========================================================================

    private FpMLConversionPipeline(String targetVersion,
                                   ConversionDeltaProfile profile,
                                   boolean failFastOnNullRelease) {
        this.targetVersion          = targetVersion;
        this.profile                = profile;
        this.failFastOnNullRelease  = failFastOnNullRelease;
    }

    private final String                 targetVersion;
    private final ConversionDeltaProfile profile;
    private final boolean                failFastOnNullRelease;
}

