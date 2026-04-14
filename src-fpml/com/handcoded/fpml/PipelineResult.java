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
 * @author Andrew Jacobs / ISDA FpML Team
 * @since  TFP 1.x
 */
public final class PipelineResult {

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a successful result.
     *
     * @param source    The source {@link Release} that was converted from.
     * @param target    The target {@link Release} that was converted to.
     * @param document  The converted DOM {@link Document}.
     * @return A successful {@link PipelineResult}.
     */
    public static PipelineResult success(Release source, Release target, Document document) {
        return new PipelineResult(true, source, target, document, Collections.<String>emptyList());
    }

    /**
     * Creates a failed result with a single diagnostic message.
     *
     * @param source  The source {@link Release} (may be {@code null} if undetectable).
     * @param target  The target {@link Release} (may be {@code null} if unresolvable).
     * @param message A human-readable failure reason.
     * @return A failed {@link PipelineResult}.
     */
    public static PipelineResult failure(Release source, Release target, String message) {
        return new PipelineResult(false, source, target, null, Collections.singletonList(message));
    }

    /**
     * Creates a failed result with multiple diagnostic messages.
     *
     * @param source   The source {@link Release} (may be {@code null}).
     * @param target   The target {@link Release} (may be {@code null}).
     * @param errors   List of human-readable failure reasons.
     * @return A failed {@link PipelineResult}.
     */
    public static PipelineResult failure(Release source, Release target, List<String> errors) {
        return new PipelineResult(false, source, target, null, Collections.unmodifiableList(errors));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return {@code true} if the conversion completed successfully. */
    public boolean isSuccess() { return success; }

    /**
     * @return The converted document on success, or {@code null} on failure.
     */
    public Document getDocument() { return document; }

    /**
     * @return The source {@link Release}; may be {@code null} if it could not
     *         be detected from the input document.
     */
    public Release getSourceRelease() { return source; }

    /**
     * @return The target {@link Release}; may be {@code null} if it could not
     *         be resolved.
     */
    public Release getTargetRelease() { return target; }

    /**
     * @return An unmodifiable list of diagnostic messages.  Empty on success.
     */
    public List<String> getErrors() { return errors; }

    /** @return A short summary string suitable for logging. */
    @Override
    public String toString() {
        if (success)
            return "PipelineResult[OK: "
                    + (source != null ? source.getVersion() : "?")
                    + " -> "
                    + (target != null ? target.getVersion() : "?")
                    + "]";
        return "PipelineResult[FAILED: " + errors + "]";
    }

    // -------------------------------------------------------------------------
    // Private constructor
    // -------------------------------------------------------------------------

    private PipelineResult(boolean success, Release source, Release target,
                           Document document, List<String> errors) {
        this.success  = success;
        this.source   = source;
        this.target   = target;
        this.document = document;
        this.errors   = errors;
    }

    private final boolean        success;
    private final Release        source;
    private final Release        target;
    private final Document       document;
    private final List<String>   errors;
}

