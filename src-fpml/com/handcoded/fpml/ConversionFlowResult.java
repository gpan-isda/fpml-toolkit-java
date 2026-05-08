// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import org.w3c.dom.Document;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result returned by {@link FpMLConversionService#executeFlow}.
 *
 * <p>Carries all diagnostic information collected during the 8-step conversion
 * flow: source schema errors (advisory), per-hop delta metadata, missing input
 * values, target schema errors, target business-rule errors, dropped fields,
 * and the converted document itself.</p>
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * ConversionFlowResult r = FpMLConversionService.INSTANCE.executeFlow(
 *         xml, null, "5-13", "confirmation", true, null);
 * if (r.isComplete()) {
 *     String converted = r.getConvertedXml();
 * } else if (r.getStatus() == ConversionFlowResult.Status.NEEDS_INPUT) {
 *     List<ProbeResult.RequiredField> fields = r.getMissingValues();
 * }
 * }</pre>
 *
 * @author ISDA FpML Team
 * @see    FpMLConversionService#executeFlow
 * @since  TFP 1.x
 */
public final class ConversionFlowResult {

    // =========================================================================
    // Status enum
    // =========================================================================

    /**
     * Describes the outcome of the 8-step conversion flow.
     */
    public enum Status {
        /** All 8 steps passed; the converted document is ready. */
        COMPLETE,
        /**
         * The {@link FpMLConversionService.RecordingRuntimeValueProvider} was
         * invoked (step 5 gate): the caller must supply the missing values and
         * re-invoke {@code executeFlow} with {@code autoProfile=false} and a
         * non-null {@code userValues} map.
         */
        NEEDS_INPUT,
        /**
         * The source document could not be parsed or its FpML release could not
         * be identified — a non-fixable structural issue.  Compare with
         * {@link #TARGET_SCHEMA_ERROR} which is a target-document issue.
         */
        SOURCE_SCHEMA_ERROR,
        /** The structural conversion step (steps 3–4) failed. */
        CONVERSION_FAILED,
        /** The converted document has XSD schema errors (step 6). */
        TARGET_SCHEMA_ERROR,
        /** The converted document has business-rule violations (step 7). */
        TARGET_RULE_ERROR
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** @return The flow outcome status. */
    public Status getStatus() { return status; }

    /** @return {@code true} when {@link Status#COMPLETE}. */
    public boolean isComplete() { return status == Status.COMPLETE; }

    /** @return The detected or caller-supplied source version (e.g. {@code "5-12"}). */
    public String getSourceVersion() { return sourceVersion; }

    /** @return The requested target version (e.g. {@code "5-13"}). */
    public String getTargetVersion() { return targetVersion; }

    /**
     * @return XSD errors found in the <em>source</em> document (advisory).
     *         Non-empty does not abort the flow; the conversion still runs.
     */
    public List<String> getSourceSchemaErrors() { return sourceSchemaErrors; }

    /**
     * @return Per-hop delta metadata derived from the loaded profile chain.
     *         May be empty if no profiles are registered for the hop sequence.
     */
    public List<HopDeltaInfo> getDeltaChain() { return deltaChain; }

    /**
     * @return Required fields that must be supplied when
     *         {@link Status#NEEDS_INPUT}.  Empty otherwise.
     */
    public List<ProbeResult.RequiredField> getMissingValues() { return missingValues; }

    /**
     * @return XSD errors found on the <em>converted</em> document.
     *         Non-empty when status is {@link Status#TARGET_SCHEMA_ERROR}.
     */
    public List<String> getTargetSchemaErrors() { return targetSchemaErrors; }

    /**
     * @return Business-rule violations found on the <em>converted</em> document.
     *         Non-empty when status is {@link Status#TARGET_RULE_ERROR}.
     */
    public List<String> getTargetBusinessRuleErrors() { return targetRuleErrors; }

    /**
     * @return Fields dropped during the structural conversion (informational).
     *         Typically non-empty only for downgrade conversions (5.x → 4.x).
     */
    public List<String> getDroppedFields() { return droppedFields; }

    /**
     * @return The converted DOM document when {@link #isComplete()}, or
     *         {@code null} if conversion did not produce a document.
     */
    public Document getConvertedDocument() { return convertedDoc; }

    /**
     * Serializes the converted document to an XML string.
     *
     * @return Serialized XML, or {@code null} if no document is available.
     */
    public String getConvertedXml() {
        if (convertedDoc == null) return null;
        return serializeDocument(convertedDoc);
    }

    /**
     * @return Non-user-fixable pipeline-level error messages (parse failures,
     *         missing conversion path, etc.).  Empty on success.
     */
    public List<String> getStructuralIssues() { return structuralIssues; }

    // =========================================================================
    // HopDeltaInfo — per-hop metadata from a ConversionDeltaProfile
    // =========================================================================

    /**
     * Metadata about a single conversion hop derived from a
     * {@link ConversionDeltaProfile}.
     */
    public static final class HopDeltaInfo {

        private final String       fromVersion;
        private final String       toVersion;
        private final String       view;
        private final List<String> requiredFields;
        private final List<String> droppedFields;
        private final List<String> modifiedFields;

        HopDeltaInfo(String fromVersion, String toVersion, String view,
                     List<String> requiredFields,
                     List<String> droppedFields,
                     List<String> modifiedFields) {
            this.fromVersion    = fromVersion;
            this.toVersion      = toVersion;
            this.view           = view;
            this.requiredFields = Collections.unmodifiableList(requiredFields);
            this.droppedFields  = Collections.unmodifiableList(droppedFields);
            this.modifiedFields = Collections.unmodifiableList(modifiedFields);
        }

        /** @return Source version for this hop (e.g. {@code "5-12"}). */
        public String       getFromVersion()   { return fromVersion; }
        /** @return Target version for this hop (e.g. {@code "5-13"}). */
        public String       getToVersion()     { return toVersion; }
        /** @return FpML view (e.g. {@code "confirmation"}), or {@code null}. */
        public String       getView()          { return view; }
        /**
         * @return Fields that a {@code prompt-at-runtime} or schema-required
         *         conditional default would request from the operator.
         */
        public List<String> getRequiredFields() { return requiredFields; }
        /**
         * @return Fields known to be dropped in this hop (informational;
         *         populated at runtime from {@link PipelineResult}).
         */
        public List<String> getDroppedFields()  { return droppedFields; }
        /**
         * @return String representations of {@code SET_ATTRIBUTE} /
         *         {@code SET_TEXT} enrichment actions in this hop's profile.
         */
        public List<String> getModifiedFields() { return modifiedFields; }

        @Override
        public String toString() {
            return "HopDeltaInfo{" + fromVersion + " → " + toVersion
                    + (view != null ? "/" + view : "")
                    + ", required=" + requiredFields.size()
                    + ", modified=" + modifiedFields.size()
                    + "}";
        }
    }

    // =========================================================================
    // Factory methods (package-private — created by FpMLConversionService)
    // =========================================================================

    /**
     * Creates a result for a completed conversion (COMPLETE, TARGET_SCHEMA_ERROR,
     * or TARGET_RULE_ERROR).
     */
    static ConversionFlowResult complete(
            Status status,
            String sourceVersion,
            String targetVersion,
            List<String> sourceSchemaErrors,
            List<HopDeltaInfo> deltaChain,
            List<String> droppedFields,
            List<String> targetSchemaErrors,
            List<String> targetRuleErrors,
            Document convertedDoc) {
        return new ConversionFlowResult(
                status, sourceVersion, targetVersion,
                sourceSchemaErrors, deltaChain,
                Collections.<ProbeResult.RequiredField>emptyList(),
                targetSchemaErrors, targetRuleErrors,
                droppedFields,
                Collections.<String>emptyList(),
                convertedDoc);
    }

    /** Creates a NEEDS_INPUT result. */
    static ConversionFlowResult needsInput(
            String sourceVersion,
            String targetVersion,
            List<String> sourceSchemaErrors,
            List<HopDeltaInfo> deltaChain,
            List<ProbeResult.RequiredField> missingValues) {
        return new ConversionFlowResult(
                Status.NEEDS_INPUT, sourceVersion, targetVersion,
                sourceSchemaErrors, deltaChain,
                Collections.unmodifiableList(missingValues),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                null);
    }

    /** Creates a failure result for structural / pipeline-level errors. */
    static ConversionFlowResult failed(
            Status status,
            String structuralIssue,
            String sourceVersion,
            String targetVersion,
            List<String> sourceSchemaErrors,
            List<HopDeltaInfo> deltaChain) {
        return new ConversionFlowResult(
                status, sourceVersion, targetVersion,
                sourceSchemaErrors, deltaChain,
                Collections.<ProbeResult.RequiredField>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                structuralIssue != null
                        ? Collections.singletonList(structuralIssue)
                        : Collections.<String>emptyList(),
                null);
    }

    // =========================================================================
    // Private constructor + serialization helper
    // =========================================================================

    private ConversionFlowResult(
            Status status,
            String sourceVersion,
            String targetVersion,
            List<String> sourceSchemaErrors,
            List<HopDeltaInfo> deltaChain,
            List<ProbeResult.RequiredField> missingValues,
            List<String> targetSchemaErrors,
            List<String> targetRuleErrors,
            List<String> droppedFields,
            List<String> structuralIssues,
            Document convertedDoc) {
        this.status             = status;
        this.sourceVersion      = sourceVersion;
        this.targetVersion      = targetVersion;
        this.sourceSchemaErrors = Collections.unmodifiableList(sourceSchemaErrors);
        this.deltaChain         = Collections.unmodifiableList(deltaChain);
        this.missingValues      = missingValues;
        this.targetSchemaErrors = Collections.unmodifiableList(targetSchemaErrors);
        this.targetRuleErrors   = Collections.unmodifiableList(targetRuleErrors);
        this.droppedFields      = Collections.unmodifiableList(droppedFields);
        this.structuralIssues   = Collections.unmodifiableList(structuralIssues);
        this.convertedDoc       = convertedDoc;
    }

    private final Status                         status;
    private final String                         sourceVersion;
    private final String                         targetVersion;
    private final List<String>                   sourceSchemaErrors;
    private final List<HopDeltaInfo>             deltaChain;
    private final List<ProbeResult.RequiredField> missingValues;
    private final List<String>                   targetSchemaErrors;
    private final List<String>                   targetRuleErrors;
    private final List<String>                   droppedFields;
    private final List<String>                   structuralIssues;
    private final Document                       convertedDoc;

    private static String serializeDocument(Document doc) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
