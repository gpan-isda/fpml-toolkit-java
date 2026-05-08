// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import org.w3c.dom.Element;

/**
 * A configurable {@link com.handcoded.meta.Helper} implementation for structural
 * FpML conversions across all asset classes and all FpML versions.
 *
 * <p>Implements {@link Conversions.ConversionHelper} and
 * {@link Conversions.DroppingHelper} so a single instance can be passed to any
 * structural conversion step in the pipeline — regardless of asset class or
 * FpML version.  Conversions are XSD-driven; there is no discrimination by
 * product type, asset class, or FX status.</p>
 *
 * <p>Values are resolved in priority order:</p>
 * <ol>
 *   <li>The {@link ConversionDeltaProfile} provided at construction time (if any).</li>
 *   <li>The {@link RuntimeValueProvider} supplied at construction time (if any) —
 *       called when the profile has no value for the key.  This is how
 *       {@link FpMLConversionService#probe} discovers required structural
 *       helper fields (kind {@code HELPER_VALUE}).</li>
 *   <li>The {@link DefaultHelper} fallback values (literal {@code "???"}).</li>
 * </ol>
 *
 * <p>Dropped-field notifications are forwarded to an optional
 * {@link Conversions.DroppingHelper} delegate (typically a
 * {@link Conversions.SimpleDroppedFieldCollector}) supplied at construction time.</p>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    ConversionDeltaProfile
 * @see    DefaultHelper
 * @see    Conversions.ConversionHelper
 * @see    Conversions.DroppingHelper
 * @since  TFP 1.x
 */
public class ConfigurableHelper implements Conversions.ConversionHelper, Conversions.DroppingHelper {

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /** Constructs a helper with no profile and no delegate — equivalent to {@link DefaultHelper}. */
    public ConfigurableHelper() { this(null, null, null); }

    /**
     * Constructs a helper that reads values from the supplied delta profile.
     * @param profile  A {@link ConversionDeltaProfile}, or {@code null}.
     */
    public ConfigurableHelper(ConversionDeltaProfile profile) { this(profile, null, null); }

    /**
     * Constructs a helper with a profile and a dropped-field delegate.
     * @param profile   A {@link ConversionDeltaProfile}, or {@code null}.
     * @param delegate  A {@link Conversions.DroppingHelper}, or {@code null}.
     */
    public ConfigurableHelper(ConversionDeltaProfile profile, Conversions.DroppingHelper delegate) {
        this(profile, delegate, null);
    }

    /**
     * Full constructor — profile, delegate, and runtime provider.
     *
     * <p>When {@code runtimeProvider} is a recording provider (as used by
     * {@link FpMLConversionService#probe}), any structural helper key not found
     * in the profile is recorded as a required field of kind {@code HELPER_VALUE},
     * surfacing it to the caller alongside {@code RUNTIME_PROMPT} fields.</p>
     *
     * @param profile          A {@link ConversionDeltaProfile}, or {@code null}.
     * @param delegate         A {@link Conversions.DroppingHelper}, or {@code null}.
     * @param runtimeProvider  A {@link RuntimeValueProvider}, or {@code null}.
     */
    public ConfigurableHelper(ConversionDeltaProfile profile,
                              Conversions.DroppingHelper delegate,
                              RuntimeValueProvider runtimeProvider) {
        this.profile         = profile;
        this.fallback        = new DefaultHelper();
        this.delegate        = delegate;
        this.runtimeProvider = runtimeProvider;
    }

    // -------------------------------------------------------------------------
    // Conversions.DroppingHelper
    // -------------------------------------------------------------------------

    @Override
    public void recordDropped(String fieldDescription) {
        if (delegate != null) delegate.recordDropped(fieldDescription);
    }

    // -------------------------------------------------------------------------
    // Conversions.ConversionHelper — single generic entry point for all asset classes
    // -------------------------------------------------------------------------

    /**
     * Returns a structural helper value by logical key.
     *
     * <p>This is the single entry point for all structural conversion value
     * requests, regardless of asset class or XSD element type.  Conversion
     * steps request values by key name (e.g. {@code "referenceCurrency"},
     * {@code "businessDayConvention"}); the key is routed through the
     * three-level priority chain: profile → runtimeProvider → fallback.</p>
     *
     * <p>When running in probe mode, every invocation is recorded as a
     * {@code HELPER_VALUE} required field in the {@link ProbeResult}.</p>
     *
     * @param key      Logical key identifying the required value.
     * @param context  The DOM element being converted.
     * @param note     Human-readable description for UI / probe response.
     * @return The resolved value; never {@code null} (falls back to {@code "???"}).
     */
    @Override
    public String getHelperValue(String key, Element context, String note) {
        // 1. Profile <helper-values>
        String v = (profile != null) ? profile.getHelperValue(key) : null;
        if (v != null) return v;

        // 2. RuntimeValueProvider — enables probe() to record HELPER_VALUE fields
        if (runtimeProvider != null) {
            String provided = runtimeProvider.provideHelperValue(key, note);
            if (provided != null && !provided.isEmpty()) return provided;
        }

        // 3. Hard-coded fallback ("???")
        return fallback.getHelperValue(key, context, note);
    }

    // -------------------------------------------------------------------------
    // Private fields
    // -------------------------------------------------------------------------

    private final ConversionDeltaProfile     profile;
    private final DefaultHelper              fallback;
    private final Conversions.DroppingHelper delegate;
    private final RuntimeValueProvider       runtimeProvider;
}
