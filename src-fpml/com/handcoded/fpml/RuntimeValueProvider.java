// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

/**
 * Callback interface for supplying default values <em>at conversion runtime</em>
 * when a {@code schema-required} field is absent in the converted document and
 * no pre-configured value is stored in the delta profile.
 *
 * <p>When a profile {@code <insert>} entry carries {@code prompt-at-runtime="true"}
 * the {@link PostConversionEnricher} delegates to this interface instead of
 * silently skipping the injection.</p>
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * FpMLConversionPipeline pipeline = new FpMLConversionPipeline.Builder("5-13")
 *         .withAutoProfile("5-12", "confirmation")
 *         .withRuntimeValueProvider(new ConsoleRuntimeValueProvider())
 *         .build();
 * }</pre>
 *
 * <p>Return {@code null} or an empty string to skip the injection for the
 * current document.</p>
 *
 * @see ConsoleRuntimeValueProvider
 * @see FpMLConversionPipeline.Builder#withRuntimeValueProvider(RuntimeValueProvider)
 * @author ISDA FpML Team
 * @since  TFP 1.x
 */
public interface RuntimeValueProvider {

    /**
     * Provides a default value for a specific missing mandatory node.
     *
     * <p>Called once per matched node (element or attribute) whose value is
     * absent or empty in the document being converted.  The {@code locationPath}
     * carries a concrete, positional XPath for the exact node (e.g.
     * {@code /dataDocument/party[2]/partyId[1]/@partyIdScheme}), while
     * {@code xpath} is the original template expression from the delta profile
     * (e.g. {@code //*[local-name()='partyId']/@partyIdScheme}).  Both are
     * provided so that implementations can choose the most appropriate key for
     * storage, display, or look-up.</p>
     *
     * @param xpath          Template XPath expression from the delta profile
     *                       (e.g. {@code //*[local-name()='foo']/@bar}).
     * @param constraintType Informational tag from the profile, e.g.
     *                       {@code "schema-required"}.  May be {@code null}.
     * @param note           Free-text reviewer note from the profile, or
     *                       {@code null} if none was recorded.
     * @param locationPath   Concrete positional XPath for this specific node
     *                       (e.g. {@code /dataDocument/party[1]/partyId[1]/@partyIdScheme}).
     *                       Implementations should display / key on this when
     *                       available so the user knows exactly which element
     *                       to fill in.
     * @return The string value to inject, or {@code null} / empty string to
     *         leave the node untouched and skip the injection for this document.
     */
    String provideValue(String xpath, String constraintType, String note, String locationPath);

    /**
     * Backward-compatible overload — delegates to
     * {@link #provideValue(String, String, String, String)} with a {@code null}
     * location path.  Callers that do not have a concrete node location can use
     * this form; implementations receive {@code null} for {@code locationPath}.
     *
     * @param xpath          Template XPath expression from the delta profile.
     * @param constraintType Informational constraint tag.  May be {@code null}.
     * @param note           Free-text reviewer note.  May be {@code null}.
     * @return The string value to inject, or {@code null} / empty to skip.
     */
    default String provideValue(String xpath, String constraintType, String note) {
        return provideValue(xpath, constraintType, note, null);
    }

    /**
     * Provides a value for a structural <em>helper</em> key (e.g.
     * {@code "referenceCurrency"}) that is required by a structural
     * {@link Conversions} step and is not present in the delta profile's
     * {@code <helper-values>} block.
     *
     * <p>The default implementation delegates to
     * {@link #provideValue(String, String, String)} using a synthetic XPath
     * derived from the key name.  Override in recording / interactive
     * implementations to produce a {@link ProbeResult.RequiredField} of kind
     * {@link ProbeResult.RequiredField.FieldKind#HELPER_VALUE} instead of
     * {@code RUNTIME_PROMPT}.</p>
     *
     * @param key   Logical helper key (e.g. {@code "referenceCurrency"}).
     * @param note  Human-readable description of the required value.
     * @return The string value to use, or {@code null} to fall through to
     *         the hard-coded default ({@code "???"}).
     */
    default String provideHelperValue(String key, String note) {
        return provideValue("//*[local-name()='" + key + "']", "schema-required", note, null);
    }
}

