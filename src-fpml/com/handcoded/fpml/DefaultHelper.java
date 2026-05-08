// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import org.w3c.dom.Element;

/**
 * The {@code DefaultHelper} class provides a fallback implementation of
 * {@link Conversions.ConversionHelper} that returns {@code "???"} for every
 * structural helper value.
 *
 * <p>This is the last-resort fallback used when no delta profile and no
 * {@link RuntimeValueProvider} are configured.  In production the pipeline
 * supplies a {@link ConfigurableHelper} instead.</p>
 *
 * <p>The helper is intentionally asset-class agnostic — any structural
 * conversion step (FX, IR, Credit, Equity, Loan, …) uses the same
 * {@link #getHelperValue(String, Element, String)} entry point.</p>
 *
 * @author  Andrew Jacobs / ISDA FpML Team
 * @since   TFP 1.3
 */
public class DefaultHelper implements Conversions.ConversionHelper {

    /**
     * Returns {@code "???"} for any structural helper key.
     *
     * <p>This sentinel value signals to downstream processing that no real
     * value was configured — callers should treat {@code "???"} as a missing
     * value and prompt the user accordingly.</p>
     *
     * @param key      Logical key name (ignored by this implementation).
     * @param context  The DOM element being converted (ignored by this implementation).
     * @param note     Human-readable description (ignored by this implementation).
     * @return {@code "???"} always.
     */
    @Override
    public String getHelperValue(String key, Element context, String note) {
        return "???";
    }
}
