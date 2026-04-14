// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import org.w3c.dom.Element;

/**
 * A configurable {@link com.handcoded.meta.Helper} implementation for structural
 * FpML conversions that require caller-supplied currency values.
 *
 * <p>Implements {@link Conversions.FxConversionHelper} — the single shared interface
 * used by both the {@code R4_0→R4_1} and {@code R4_1→R4_2} structural conversion
 * steps, so one instance can be passed to either.</p>
 *
 * <p>Values are resolved in priority order:</p>
 * <ol>
 *   <li>The {@link ConversionDeltaProfile} provided at construction time (if any).</li>
 *   <li>The {@link DefaultHelper} fallback values (literal {@code "???"}).</li>
 * </ol>
 *
 * <p>Applications that need real currency values should provide a
 * {@link ConversionDeltaProfile} loaded from a delta-profile XML file, or
 * subclass and override individual methods.</p>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    ConversionDeltaProfile
 * @see    DefaultHelper
 * @since  TFP 1.x
 */
public class ConfigurableHelper implements Conversions.FxConversionHelper {

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Constructs a helper with no profile, equivalent to {@link DefaultHelper}.
     */
    public ConfigurableHelper() {
        this(null);
    }

    /**
     * Constructs a helper that reads values from the supplied delta profile.
     *
     * @param profile  A {@link ConversionDeltaProfile}, or {@code null} to use
     *                 {@link DefaultHelper} fallbacks only.
     */
    public ConfigurableHelper(ConversionDeltaProfile profile) {
        this.profile  = profile;
        this.fallback = new DefaultHelper();
    }

    // -------------------------------------------------------------------------
    // Conversions.FxConversionHelper
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>Returns the value of the {@code referenceCurrency} key from the
     * profile, or the {@link DefaultHelper} fallback if absent.</p>
     */
    @Override
    public String getReferenceCurrency(final Element context) {
        String v = profileValue("referenceCurrency");
        return v != null ? v : fallback.getReferenceCurrency(context);
    }

    /**
     * {@inheritDoc}
     * <p>Returns the value of the {@code quantoCurrency1} key from the
     * profile, or the {@link DefaultHelper} fallback if absent.</p>
     */
    @Override
    public String getQuantoCurrency1(final Element context) {
        String v = profileValue("quantoCurrency1");
        return v != null ? v : fallback.getQuantoCurrency1(context);
    }

    /**
     * {@inheritDoc}
     * <p>Returns the value of the {@code quantoCurrency2} key from the
     * profile, or the {@link DefaultHelper} fallback if absent.</p>
     */
    @Override
    public String getQuantoCurrency2(final Element context) {
        String v = profileValue("quantoCurrency2");
        return v != null ? v : fallback.getQuantoCurrency2(context);
    }

    /**
     * {@inheritDoc}
     * <p>Returns the value of the {@code quantoCurrencyBasis} key from the
     * profile, or the {@link DefaultHelper} fallback if absent.</p>
     */
    @Override
    public String getQuantoCurrencyBasis(final Element context) {
        String v = profileValue("quantoCurrencyBasis");
        return v != null ? v : fallback.getQuantoCurrencyBasis(context);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String profileValue(String key) {
        return (profile != null) ? profile.getHelperValue(key) : null;
    }

    private final ConversionDeltaProfile profile;
    private final DefaultHelper          fallback;
}

