// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import java.util.Collections;
import java.util.Map;

/**
 * A {@link RuntimeValueProvider} backed by a caller-supplied {@link Map}.
 *
 * <p>Lookup priority:</p>
 * <ol>
 *   <li>Exact XPath key match — {@code values.get(xpath)}.</li>
 *   <li>Short-name key extracted from the XPath (last {@code @attr} or
 *       {@code element} token) — e.g. the XPath
 *       {@code //*[local-name()='foo']/@bar} yields the key {@code "bar"}.</li>
 * </ol>
 *
 * <p>Returns {@code null} when neither key is found in the map, which
 * causes the pipeline to leave the field absent (same semantics as a
 * {@code null} return from any other {@link RuntimeValueProvider}).</p>
 *
 * <p>Typical usage — closing the interactive probe→fill→convert loop:</p>
 * <pre>{@code
 * ProbeResult probe = FpMLConversionService.INSTANCE.probe(xml, "5-13", "confirmation");
 * if (!probe.isComplete()) {
 *     Map<String,String> answers = collectFromUI(probe.getRequiredFields());
 *     PipelineResult result = FpMLConversionService.INSTANCE.convert(
 *             xml, "5-13", "confirmation", answers);
 * }
 * }</pre>
 *
 * @author ISDA FpML Team
 * @see    FpMLConversionService
 * @see    ProbeResult
 * @since  TFP 1.x
 */
public final class MapRuntimeValueProvider implements RuntimeValueProvider {

    /**
     * Creates a provider backed by the given map.
     *
     * @param values  Key→value map.  The map is copied defensively so later
     *                mutations by the caller do not affect this provider.
     *                Must not be {@code null}.
     */
    public MapRuntimeValueProvider(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * Looks up the value for the missing node.
     *
     * <p>First tries the full {@code xpath} string as a key; if not found,
     * extracts the short name from the XPath tail and tries that.</p>
     *
     * @return The mapped value, or {@code null} if not found.
     */
    @Override
    public String provideValue(String xpath, String constraintType, String note,
                               String locationPath) {
        // 1. Exact concrete location path key (most specific)
        if (locationPath != null && !locationPath.isEmpty()) {
            String value = values.get(locationPath);
            if (value != null && !value.isEmpty()) return value;
        }

        // 2. Exact xpath key
        String value = values.get(xpath);
        if (value != null && !value.isEmpty()) return value;

        // 3. Short-name key from the XPath tail
        String key = extractKey(xpath);
        if (key != null) {
            value = values.get(key);
            if (value != null && !value.isEmpty()) return value;
        }

        return null;
    }

    /**
     * Looks up a structural helper value directly by its logical key name
     * (e.g. {@code "referenceCurrency"}).  This is the most direct path for
     * helper values supplied via the probe→fill→convert loop.
     */
    @Override
    public String provideHelperValue(String key, String note) {
        String value = values.get(key);
        return (value != null && !value.isEmpty()) ? value : null;
    }

    // -------------------------------------------------------------------------

    private final Map<String, String> values;

    /**
     * Extracts a short lookup key from an XPath expression.
     *
     * <ul>
     *   <li>{@code ...//@attr}  → {@code "attr"}</li>
     *   <li>{@code //element}   → {@code "element"}</li>
     *   <li>{@code //*[@attr]}  → {@code "attr"}</li>
     * </ul>
     */
    private static String extractKey(String xpath) {
        if (xpath == null || xpath.isEmpty()) return null;

        // Last @attr token (handles //@attr and /@attr forms)
        int atIdx = xpath.lastIndexOf('@');
        if (atIdx >= 0) {
            String after = xpath.substring(atIdx + 1);
            return extractIdentifier(after);
        }

        // Last path segment (e.g. //foo/bar → "bar")
        int slashIdx = xpath.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < xpath.length() - 1) {
            return extractIdentifier(xpath.substring(slashIdx + 1));
        }

        return extractIdentifier(xpath);
    }

    /** Extracts the leading XML identifier characters from {@code s}. */
    private static String extractIdentifier(String s) {
        if (s == null || s.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')
                sb.append(c);
            else
                break;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
