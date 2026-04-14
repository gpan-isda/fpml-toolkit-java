// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import com.handcoded.meta.Release;
import org.w3c.dom.Document;

import java.util.Collections;
import java.util.List;

/**
 * Immutable result object returned by {@link FpMLConversionPipeline#convert}.
 *
 * <p>On success {@link #isSuccess()} returns {@code true} and
 * {@link #getDocument()} returns the converted DOM document.
 * On failure the document may be {@code null} and {@link #getErrors()} contains
 * human-readable diagnostics.</p>
 *
 * <p>Additional detail lists are always present (never {@code null}), but may
 * be empty:</p>
 * <ul>
 *   <li>{@link #getInboundErrors()}  — validation errors on the <em>source</em> document.</li>
 *   <li>{@link #getOutboundErrors()} — validation errors on the <em>converted</em> document.</li>
 *   <li>{@link #getDroppedFields()}  — field descriptions dropped during a downgrade conversion
 *       (e.g. 5.x → 4.x via {@link Conversions.R5_0_CONF__R4_10}).</li>
 * </ul>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @since  TFP 1.x
 */
public final class PipelineResult {

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a successful result with no validation errors and no dropped fields.
     */
    public static PipelineResult success(Release source, Release target, Document document) {
        return new PipelineResult(true, source, target, document,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    /**
     * Creates a failed result with a single pipeline-level diagnostic message.
     */
    public static PipelineResult failure(Release source, Release target, String message) {
        return new PipelineResult(false, source, target, null,
                Collections.singletonList(message),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    /**
     * Creates a failed result with multiple pipeline-level diagnostic messages.
     */
    public static PipelineResult failure(Release source, Release target, List<String> errors) {
        return new PipelineResult(false, source, target, null,
                Collections.unmodifiableList(errors),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    /**
     * Creates a fully-populated result carrying all three diagnostic lists.
     *
     * @param success         {@code true} if conversion completed (validation failures are advisory).
     * @param source          Source release.
     * @param target          Target release.
     * @param document        Converted document (may be {@code null} on hard failure).
     * @param errors          Pipeline-level errors (e.g. "no conversion path found").
     * @param inboundErrors   Validation messages for the source document.
     * @param outboundErrors  Validation messages for the converted document.
     * @param droppedFields   Field descriptions dropped during a downgrade.
     */
    public static PipelineResult full(boolean success,
                                      Release source, Release target, Document document,
                                      List<String> errors,
                                      List<String> inboundErrors,
                                      List<String> outboundErrors,
                                      List<String> droppedFields) {
        return new PipelineResult(success, source, target, document,
                Collections.unmodifiableList(errors),
                Collections.unmodifiableList(inboundErrors),
                Collections.unmodifiableList(outboundErrors),
                Collections.unmodifiableList(droppedFields));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return {@code true} if the conversion completed (not necessarily validation-clean). */
    public boolean isSuccess() { return success; }

    /** @return The converted document on success, or {@code null} on hard failure. */
    public Document getDocument() { return document; }

    /** @return The source {@link Release}; may be {@code null} if undetectable. */
    public Release getSourceRelease() { return source; }

    /** @return The target {@link Release}; may be {@code null} if unresolvable. */
    public Release getTargetRelease() { return target; }

    /** @return Pipeline-level diagnostic messages (empty on success). */
    public List<String> getErrors() { return errors; }

    /** @return Semantic validation errors on the <em>source</em> document (may be empty). */
    public List<String> getInboundErrors()  { return inboundErrors; }

    /** @return Semantic validation errors on the <em>converted</em> document (may be empty). */
    public List<String> getOutboundErrors() { return outboundErrors; }

    /**
     * @return Human-readable descriptions of fields/attributes that were dropped during
     *         a downgrade conversion (e.g. {@code "@fpmlVersion=5-0"}).  Empty on upgrades.
     */
    public List<String> getDroppedFields()  { return droppedFields; }

    /** @return {@code true} if there are any inbound or outbound validation errors. */
    public boolean hasValidationErrors() {
        return !inboundErrors.isEmpty() || !outboundErrors.isEmpty();
    }

    /** @return A short summary string suitable for logging. */
    @Override
    public String toString() {
        if (success) {
            StringBuilder sb = new StringBuilder("PipelineResult[OK: ")
                    .append(source != null ? source.getVersion() : "?")
                    .append(" -> ")
                    .append(target != null ? target.getVersion() : "?");
            if (!droppedFields.isEmpty())
                sb.append(", dropped=").append(droppedFields.size());
            if (!inboundErrors.isEmpty())
                sb.append(", inboundErrors=").append(inboundErrors.size());
            if (!outboundErrors.isEmpty())
                sb.append(", outboundErrors=").append(outboundErrors.size());
            return sb.append("]").toString();
        }
        return "PipelineResult[FAILED: " + errors + "]";
    }

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private PipelineResult(boolean success, Release source, Release target,
                           Document document, List<String> errors,
                           List<String> inboundErrors, List<String> outboundErrors,
                           List<String> droppedFields) {
        this.success        = success;
        this.source         = source;
        this.target         = target;
        this.document       = document;
        this.errors         = errors;
        this.inboundErrors  = inboundErrors;
        this.outboundErrors = outboundErrors;
        this.droppedFields  = droppedFields;
    }

    private final boolean        success;
    private final Release        source;
    private final Release        target;
    private final Document       document;
    private final List<String>   errors;
    private final List<String>   inboundErrors;
    private final List<String>   outboundErrors;
    private final List<String>   droppedFields;
}
