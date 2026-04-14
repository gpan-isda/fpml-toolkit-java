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
 * <p>Implements both {@link Conversions.FxConversionHelper} and
 * {@link Conversions.DroppingHelper} so a single instance can be passed to any
 * structural conversion step in the pipeline.</p>
 *
 * <p>Values are resolved in priority order:</p>
 * <ol>
 *   <li>The {@link ConversionDeltaProfile} provided at construction time (if any).</li>
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
 * @see    Conversions.DroppingHelper
 * @since  TFP 1.x
 */
public class ConfigurableHelper implements Conversions.DroppingHelper {

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Constructs a helper with no profile and no dropping delegate,
     * equivalent to {@link DefaultHelper}.
     */
    public ConfigurableHelper() {
        this(null, null);
    }

    /**
     * Constructs a helper that reads values from the supplied delta profile.
     *
     * @param profile  A {@link ConversionDeltaProfile}, or {@code null} to use
     *                 {@link DefaultHelper} fallbacks only.
     */
    public ConfigurableHelper(ConversionDeltaProfile profile) {
        this(profile, null);
    }

    /**
     * Constructs a helper that reads values from the supplied delta profile and
     * forwards dropped-field notifications to the given delegate.
     *
     * @param profile   A {@link ConversionDeltaProfile}, or {@code null} for fallbacks.
     * @param delegate  A {@link Conversions.DroppingHelper} that will receive
     *                  {@link #recordDropped} calls, or {@code null} to discard them.
     */
    public ConfigurableHelper(ConversionDeltaProfile profile,
                              Conversions.DroppingHelper delegate) {
        this.profile  = profile;
        this.fallback = new DefaultHelper();
        this.delegate = delegate;
    }

    // -------------------------------------------------------------------------
    // Conversions.DroppingHelper
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>Forwarded to the delegate if one was supplied at construction time;
     * otherwise discarded silently.</p>
     */
    @Override
    public void recordDropped(String fieldDescription) {
        if (delegate != null) delegate.recordDropped(fieldDescription);
    }

    // -------------------------------------------------------------------------
    // Conversions.FxConversionHelper
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public String getReferenceCurrency(final Element context) {
        String v = profileValue("referenceCurrency");
        return v != null ? v : fallback.getReferenceCurrency(context);
    }

    /** {@inheritDoc} */
    @Override
    public String getQuantoCurrency1(final Element context) {
        String v = profileValue("quantoCurrency1");
        return v != null ? v : fallback.getQuantoCurrency1(context);
    }

    /** {@inheritDoc} */
    @Override
    public String getQuantoCurrency2(final Element context) {
        String v = profileValue("quantoCurrency2");
        return v != null ? v : fallback.getQuantoCurrency2(context);
    }

    /** {@inheritDoc} */
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

    private final ConversionDeltaProfile  profile;
    private final DefaultHelper           fallback;
    private final Conversions.DroppingHelper delegate;
}
