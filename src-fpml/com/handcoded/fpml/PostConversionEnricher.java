// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import com.handcoded.fpml.ConversionDeltaProfile.EnrichAction;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.List;
import java.util.logging.Logger;

/**
 * Applies a sequence of {@link EnrichAction} mutations to a converted FpML
 * {@link Document}.
 *
 * <p>Enrichment actions are described in the {@code <enrich>} block of a
 * {@link ConversionDeltaProfile}.  Each action specifies an XPath 1.0
 * expression that selects one or more nodes, and a mutation to apply:</p>
 * <ul>
 *   <li>{@code SET_ATTRIBUTE}  — add or replace an XML attribute.</li>
 *   <li>{@code REMOVE_ATTRIBUTE} — remove an attribute if present.</li>
 *   <li>{@code SET_TEXT}       — replace the text content of an element.</li>
 * </ul>
 *
 * <p>XPath expressions are evaluated with a namespace-unaware context for
 * simplicity; use {@code local-name()} predicates when namespace-qualified
 * selection is needed.</p>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    ConversionDeltaProfile
 * @since  TFP 1.x
 */
public final class PostConversionEnricher {

    private static final Logger LOG = Logger.getLogger(PostConversionEnricher.class.getName());

    /**
     * Applies all enrichment actions from the supplied {@link ConversionDeltaProfile}
     * to the given {@link Document}.
     *
     * <p>Actions are applied in declaration order; XPath failures for individual
     * actions are logged as warnings and do not stop the remaining actions.</p>
     *
     * @param document  The converted DOM document to mutate in-place.
     * @param profile   The delta profile supplying the enrichment actions.
     *                  May be {@code null}, in which case this method is a no-op.
     */
    public static void enrich(Document document, ConversionDeltaProfile profile) {
        if (profile == null || document == null) return;

        List<EnrichAction> actions = profile.getEnrichActions();
        if (actions.isEmpty()) return;

        XPath xpath = XPathFactory.newInstance().newXPath();

        for (EnrichAction action : actions) {
            try {
                applyAction(document, xpath, action);
            } catch (XPathExpressionException e) {
                LOG.warning("Enrichment action skipped (XPath error): "
                        + action + " — " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private implementation
    // -------------------------------------------------------------------------

    private PostConversionEnricher() { }

    private static void applyAction(Document document, XPath xpath, EnrichAction action)
            throws XPathExpressionException {

        NodeList nodes = (NodeList) xpath.evaluate(
                action.getXpath(), document, XPathConstants.NODESET);

        if (nodes == null || nodes.getLength() == 0) {
            LOG.fine("Enrichment action matched no nodes: " + action.getXpath());
            return;
        }

        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node node = nodes.item(i);

            switch (action.getType()) {
                case SET_ATTRIBUTE: {
                    Element elem = asElement(node);
                    if (elem == null) break;
                    String attr = action.getAttribute() != null
                            ? action.getAttribute()
                            : localName(action.getXpath());
                    String val  = action.getValue() != null ? action.getValue() : "";
                    elem.setAttribute(attr, val);
                    break;
                }
                case REMOVE_ATTRIBUTE: {
                    Element elem = asElement(node);
                    if (elem == null) break;
                    elem.removeAttribute(action.getAttribute());
                    break;
                }
                case SET_TEXT: {
                    Element elem = asElement(node);
                    if (elem == null) break;
                    // Remove existing text nodes
                    while (elem.hasChildNodes())
                        elem.removeChild(elem.getFirstChild());
                    // Append new text
                    String val = action.getValue() != null ? action.getValue() : "";
                    elem.appendChild(document.createTextNode(val));
                    break;
                }
                default:
                    LOG.warning("Unknown enrichment action type: " + action.getType());
            }
        }
    }

    /** Returns the node cast to {@link Element}, or {@code null} if it is not one. */
    private static Element asElement(org.w3c.dom.Node node) {
        return (node instanceof Element) ? (Element) node : null;
    }

    /**
     * Best-effort extraction of a local name from the tail of an XPath expression
     * for use as an attribute name when {@code attribute} is not set explicitly.
     * E.g., {@code "//*[@fpmlVersion]"} → {@code "fpmlVersion"}.
     */
    private static String localName(String xpath) {
        int at = xpath.lastIndexOf('@');
        if (at < 0) return xpath;
        String tail = xpath.substring(at + 1);
        // Strip trailing ] or other XPath punctuation
        int end = tail.length();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                end = i;
                break;
            }
        }
        return tail.substring(0, end);
    }
}

