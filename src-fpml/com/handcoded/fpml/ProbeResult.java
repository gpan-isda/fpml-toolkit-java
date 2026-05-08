// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import java.util.Collections;
import java.util.List;

/**
 * Returned by {@link FpMLConversionService#probe} to describe whether a
 * conversion can complete immediately or requires additional caller-supplied
 * field values.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * ProbeResult probe = FpMLConversionService.INSTANCE.probe(xml, "5-13", "confirmation");
 * if (probe.isComplete()) {
 *     // Conversion ran; result is in probe.getConversionResult()
 *     PipelineResult result = probe.getConversionResult();
 * } else {
 *     // Render a form with probe.getRequiredFields() and re-submit
 *     for (ProbeResult.RequiredField f : probe.getRequiredFields()) {
 *         System.out.printf("Field: %s (%s)%n", f.getName(), f.getLabel());
 *     }
 * }
 * }</pre>
 *
 * @author ISDA FpML Team
 * @see    FpMLConversionService
 * @since  TFP 1.x
 */
public final class ProbeResult {

    // =========================================================================
    // Factory methods (package-private — created by FpMLConversionService)
    // =========================================================================

    /** Creates a complete result: conversion ran, no missing fields. */
    static ProbeResult complete(PipelineResult conversionResult) {
        return new ProbeResult(true, Collections.<RequiredField>emptyList(),
                Collections.<String>emptyList(), conversionResult);
    }

    /** Creates an incomplete result: required fields must be supplied. */
    static ProbeResult incomplete(List<RequiredField> requiredFields) {
        return new ProbeResult(false,
                Collections.unmodifiableList(requiredFields),
                Collections.<String>emptyList(), null);
    }

    /**
     * Creates an incomplete result that carries source-document validation errors.
     * The caller must fix the source document before retrying — these errors are
     * not resolvable by supplying field values.
     */
    static ProbeResult withSourceErrors(List<RequiredField> requiredFields,
                                        List<String> sourceValidationErrors) {
        return new ProbeResult(false,
                Collections.unmodifiableList(requiredFields),
                Collections.unmodifiableList(sourceValidationErrors), null);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * @return {@code true} if the conversion completed successfully and
     *         {@link #getConversionResult()} is populated; {@code false} if
     *         {@link #getRequiredFields()} is non-empty or
     *         {@link #getSourceValidationErrors()} is non-empty and the caller must
     *         supply values or fix the source document before retrying.
     */
    public boolean isComplete() { return complete; }

    /**
     * @return Non-empty list of fields the caller must supply when
     *         {@code !isComplete()}.  Empty on a complete result.
     */
    public List<RequiredField> getRequiredFields() { return requiredFields; }

    /**
     * @return Non-empty list of XSD / business-rule validation error messages
     *         found in the <em>source</em> document.  The caller must fix the
     *         source document — these errors cannot be resolved by supplying
     *         field values.  Empty when {@code isComplete()} is {@code true}.
     */
    public List<String> getSourceValidationErrors() { return sourceValidationErrors; }

    /**
     * @return {@code true} if the probe found source-document validation errors
     *         (a convenience check for {@code !getSourceValidationErrors().isEmpty()}).
     */
    public boolean hasSourceValidationErrors() { return !sourceValidationErrors.isEmpty(); }

    /**
     * @return The {@link PipelineResult} produced by the conversion run when
     *         {@code isComplete()} is {@code true}; {@code null} otherwise.
     */
    public PipelineResult getConversionResult() { return conversionResult; }

    // =========================================================================
    // RequiredField descriptor
    // =========================================================================

    /**
     * Describes a single field that the caller must provide before the
     * conversion can proceed.
     */
    public static final class RequiredField {

        /** Discriminates between structural helper values, runtime prompts, and validation errors. */
        public enum FieldKind {
            /** Value needed by a structural conversion helper (e.g. FX currencies). */
            HELPER_VALUE,
            /** Value needed by a {@code prompt-at-runtime} conditional-default entry. */
            RUNTIME_PROMPT,
            /**
             * A validation error found in the source document (XSD or business-rule).
             * The caller must fix the source document before re-submitting — no user
             * value can be supplied to resolve this kind of field.
             */
            SOURCE_VALIDATION_ERROR
        }

        private final String    name;
        private final String    label;
        private final String    defaultValue;
        private final FieldKind kind;

        RequiredField(String name, String label, String defaultValue, FieldKind kind) {
            this.name         = name;
            this.label        = label;
            this.defaultValue = defaultValue;
            this.kind         = kind;
        }

        /**
         * @return The map key to use when supplying the value back via
         *         {@link FpMLConversionService#convert(String, String, String, java.util.Map)}
         *         (e.g. {@code "referenceCurrency"}).
         */
        public String    getName()         { return name; }

        /** @return Human-readable display label for a UI form. */
        public String    getLabel()        { return label; }

        /**
         * @return Suggested / default value for the field (may be empty string
         *         if no default is known).
         */
        public String    getDefaultValue() { return defaultValue; }

        /** @return Whether this is a structural helper value or a runtime prompt. */
        public FieldKind getKind()         { return kind; }

        @Override
        public String toString() {
            return "RequiredField{name=" + name + ", kind=" + kind
                    + (defaultValue != null && !defaultValue.isEmpty()
                       ? ", default=" + defaultValue : "") + "}";
        }
    }

    // =========================================================================
    // Private constructor
    // =========================================================================

    private ProbeResult(boolean complete, List<RequiredField> requiredFields,
                        List<String> sourceValidationErrors,
                        PipelineResult conversionResult) {
        this.complete               = complete;
        this.requiredFields         = requiredFields;
        this.sourceValidationErrors = sourceValidationErrors;
        this.conversionResult       = conversionResult;
    }

    private final boolean            complete;
    private final List<RequiredField> requiredFields;
    private final List<String>        sourceValidationErrors;
    private final PipelineResult     conversionResult;
}
