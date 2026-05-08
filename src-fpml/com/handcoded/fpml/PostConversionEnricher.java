// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import com.handcoded.fpml.ConversionDeltaProfile.EnrichAction;
import com.handcoded.fpml.ConversionDeltaProfile.ConditionalDefault;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        // Log rename actions — runtime execution is not yet implemented.
        List<ConversionDeltaProfile.RenameAction> renames = profile.getRenameActions();
        if (!renames.isEmpty()) {
            LOG.info("Profile contains " + renames.size()
                    + " rename action(s) — runtime rename support is not yet implemented; skipping.");
            for (ConversionDeltaProfile.RenameAction ra : renames)
                LOG.fine("  Skipped rename: " + ra);
        }
    }

    /**
     * Applies conditional default-value injections from the supplied
     * {@link ConversionDeltaProfile} to the given {@link Document}.
     *
     * <p>Equivalent to calling
     * {@link #applyConditionalDefaults(Document, ConversionDeltaProfile, RuntimeValueProvider)}
     * with a {@code null} value provider — {@code prompt-at-runtime} entries are
     * silently skipped.</p>
     *
     * @param document  The converted DOM document to mutate in-place.
     * @param profile   The delta profile.  May be {@code null} (no-op).
     */
    public static void applyConditionalDefaults(Document document,
                                                ConversionDeltaProfile profile) {
        applyConditionalDefaults(document, profile, null);
    }

    /**
     * Applies conditional default-value injections from the supplied
     * {@link ConversionDeltaProfile} to the given {@link Document}.
     *
     * <p>Each {@link ConditionalDefault} entry is evaluated against the
     * converted document.  Injection is skipped when:</p>
     * <ul>
     *   <li>The document's detected view does not match the entry's {@code view}
     *       scope (when set).</li>
     *   <li>The entry's {@code productType} is set and no matching product element
     *       is found in the document.</li>
     *   <li>{@code onlyIfAbsent} is {@code true} (the default) and the target
     *       node already exists with a non-empty value.</li>
     * </ul>
     *
     * <p>When an entry carries {@code promptAtRuntime=true} and its stored
     * {@code value} is blank, the supplied {@code valueProvider} is called to
     * obtain the value at runtime (only when the node is actually absent or empty
     * in the document, to avoid unnecessary prompts).  If {@code valueProvider} is
     * {@code null} such entries are silently skipped.</p>
     *
     * @param document       The converted DOM document to mutate in-place.
     * @param profile        The delta profile.  May be {@code null} (no-op).
     * @param valueProvider  Provider for runtime-prompted values.  May be
     *                       {@code null} to silently skip prompt-at-runtime entries.
     */
    public static void applyConditionalDefaults(Document document,
                                                ConversionDeltaProfile profile,
                                                RuntimeValueProvider valueProvider) {
        if (profile == null || document == null) return;

        List<ConditionalDefault> defaults = profile.getConditionalDefaults();
        if (defaults.isEmpty()) return;

        String      docView         = detectView(document);
        Set<String> docProductTypes = detectProductTypes(document);
        XPath       xpathEngine     = XPathFactory.newInstance().newXPath();

        for (ConditionalDefault cd : defaults) {
            // --- scope: view ---
            if (cd.getView() != null && !cd.getView().equalsIgnoreCase(docView))
                continue;
            // --- scope: product-type ---
            if (cd.getProductType() != null
                    && !docProductTypes.contains(cd.getProductType()))
                continue;

            String templateXpath = cd.getXpath();
            int atMarker = templateXpath.lastIndexOf("/@");
            boolean isAttrTarget = atMarker >= 0;

            if (cd.isPromptAtRuntime() && (cd.getValue() == null || cd.getValue().isEmpty())) {
                // ---- Per-node prompting: evaluate XPath, iterate matched nodes individually ----
                if (valueProvider == null) {
                    LOG.fine("Skipping prompt-at-runtime entry (no provider): " + templateXpath);
                    continue;
                }
                try {
                    if (isAttrTarget) {
                        String parentXpath = templateXpath.substring(0, atMarker);
                        String attrName    = cleanName(templateXpath.substring(atMarker + 2));
                        NodeList parents = (NodeList) xpathEngine.evaluate(
                                parentXpath, document, XPathConstants.NODESET);
                        if (parents == null || parents.getLength() == 0) {
                            LOG.fine("prompt-at-runtime: no parent nodes for: " + parentXpath);
                            continue;
                        }
                        for (int i = 0; i < parents.getLength(); i++) {
                            Element elem = (parents.item(i) instanceof Element)
                                    ? (Element) parents.item(i) : null;
                            if (elem == null) continue;
                            // Skip if already set
                            String existing = elem.getAttribute(attrName);
                            if (cd.isOnlyIfAbsent() && existing != null && !existing.isEmpty()) {
                                LOG.fine("prompt-at-runtime: attribute already set on "
                                        + elem.getLocalName() + ", skipping");
                                continue;
                            }
                            String locationPath = buildLocationPath(elem) + "/@" + attrName;
                            String value = valueProvider.provideValue(
                                    templateXpath, cd.getConstraintType(), cd.getNote(),
                                    locationPath);
                            if (value == null || value.isEmpty()) {
                                LOG.fine("prompt-at-runtime: user skipped " + locationPath);
                                continue;
                            }
                            elem.setAttribute(attrName, value);
                            LOG.fine("Conditional default: set @" + attrName + "=" + value
                                    + " on " + locationPath);
                        }
                    } else {
                        // Element-text target
                        NodeList nodes = (NodeList) xpathEngine.evaluate(
                                templateXpath, document, XPathConstants.NODESET);
                        if (nodes == null || nodes.getLength() == 0) {
                            LOG.fine("prompt-at-runtime: no nodes for: " + templateXpath);
                            continue;
                        }
                        for (int i = 0; i < nodes.getLength(); i++) {
                            Element elem = (nodes.item(i) instanceof Element)
                                    ? (Element) nodes.item(i) : null;
                            if (elem == null) continue;
                            String existing = elem.getTextContent();
                            if (cd.isOnlyIfAbsent() && existing != null
                                    && !existing.trim().isEmpty()) {
                                LOG.fine("prompt-at-runtime: element already has text on "
                                        + elem.getLocalName() + ", skipping");
                                continue;
                            }
                            String locationPath = buildLocationPath(elem);
                            String value = valueProvider.provideValue(
                                    templateXpath, cd.getConstraintType(), cd.getNote(),
                                    locationPath);
                            if (value == null || value.isEmpty()) {
                                LOG.fine("prompt-at-runtime: user skipped " + locationPath);
                                continue;
                            }
                            while (elem.hasChildNodes())
                                elem.removeChild(elem.getFirstChild());
                            elem.appendChild(document.createTextNode(value));
                            LOG.fine("Conditional default: set text of " + locationPath
                                    + "=" + value);
                        }
                    }
                } catch (XPathExpressionException e) {
                    LOG.warning("Conditional default skipped (XPath error): "
                            + templateXpath + " — " + e.getMessage());
                }
            } else {
                // ---- Pre-configured value: apply to all matched nodes at once ----
                String value = cd.getValue();
                if (value == null || value.isEmpty()) continue;

                try {
                    applyConditionalDefaultWithValue(document, xpathEngine, cd, value);
                } catch (XPathExpressionException e) {
                    LOG.warning("Conditional default skipped (XPath error): "
                            + templateXpath + " — " + e.getMessage());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers for conditional defaults
    // -------------------------------------------------------------------------
    private static String detectView(Document document) {
        Element root = document.getDocumentElement();
        if (root == null) return null;
        String ns = root.getNamespaceURI();
        if (ns == null) return null;
        // e.g. "http://www.fpml.org/FpML-5/confirmation"
        int slash = ns.lastIndexOf('/');
        if (slash >= 0 && slash < ns.length() - 1)
            return ns.substring(slash + 1).toLowerCase();
        return null;
    }

    /**
     * Collects the local names of all immediate child elements of the document
     * root that could be product-type containers (heuristic: any element that is
     * not a common wrapper like "trade", "tradeHeader", "header", "party").
     */
    private static Set<String> detectProductTypes(Document document) {
        Set<String> products = new HashSet<>();
        Element root = document.getDocumentElement();
        if (root == null) return products;
        org.w3c.dom.NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            String local = node.getLocalName();
            if (local == null) local = node.getNodeName();
            products.add(local);
            // Also recurse one level into <trade> to find the actual product
            if ("trade".equalsIgnoreCase(local)) {
                org.w3c.dom.NodeList tradeChildren = ((Element) node).getChildNodes();
                for (int j = 0; j < tradeChildren.getLength(); j++) {
                    org.w3c.dom.Node tc = tradeChildren.item(j);
                    if (tc.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                        String tl = tc.getLocalName();
                        if (tl != null) products.add(tl);
                    }
                }
            }
        }
        return products;
    }

    /**
     * Builds a concrete, positional XPath location string for the given element,
     * walking up the DOM to the document root.
     *
     * <p>For example, if the element is the second {@code <partyId>} inside the
     * first {@code <party>} child of {@code <dataDocument>}, the result is
     * {@code /dataDocument/party[1]/partyId[2]}.</p>
     *
     * @param elem  The element whose location path is required.
     * @return A concrete XPath string (never {@code null}).
     */
    private static String buildLocationPath(Element elem) {
        StringBuilder sb = new StringBuilder();
        Node current = elem;
        while (current != null && current.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) current;
            String name = el.getLocalName();
            if (name == null) name = el.getNodeName();

            // Count siblings with the same local name to produce a position predicate
            int pos = 1;
            Node sib = el.getPreviousSibling();
            while (sib != null) {
                if (sib.getNodeType() == Node.ELEMENT_NODE) {
                    String sibName = sib.getLocalName();
                    if (sibName == null) sibName = sib.getNodeName();
                    if (name.equals(sibName)) pos++;
                }
                sib = sib.getPreviousSibling();
            }

            // Count total siblings to decide if predicate is needed
            int total = pos;
            sib = el.getNextSibling();
            while (sib != null) {
                if (sib.getNodeType() == Node.ELEMENT_NODE) {
                    String sibName = sib.getLocalName();
                    if (sibName == null) sibName = sib.getNodeName();
                    if (name.equals(sibName)) total++;
                }
                sib = sib.getNextSibling();
            }

            String segment = (total > 1) ? name + "[" + pos + "]" : name;
            sb.insert(0, "/" + segment);
            current = current.getParentNode();
        }
        return sb.toString();
    }

    /**
     * Injects {@code value} into the node(s) selected by
     * {@code cd.getXpath()}, respecting the {@code onlyIfAbsent} guard.
     * Attribute injection: XPath must end with {@code /@attrName}.
     * Element-text injection: XPath selects an element node.
     */
    private static void applyConditionalDefaultWithValue(Document document,
                                                         XPath xpath,
                                                         ConditionalDefault cd,
                                                         String value)
            throws XPathExpressionException {

        String xpathExpr = cd.getXpath();
        int atMarker = xpathExpr.lastIndexOf("/@");
        if (atMarker >= 0) {
            // --- Attribute injection ---
            String parentXpath = xpathExpr.substring(0, atMarker);
            String attrName    = cleanName(xpathExpr.substring(atMarker + 2));
            NodeList parents = (NodeList) xpath.evaluate(
                    parentXpath, document, XPathConstants.NODESET);
            if (parents == null || parents.getLength() == 0) {
                LOG.fine("Conditional default: no parent nodes matched: " + parentXpath);
                return;
            }
            for (int i = 0; i < parents.getLength(); i++) {
                Element elem = (parents.item(i) instanceof Element)
                        ? (Element) parents.item(i) : null;
                if (elem == null) continue;
                String existing = elem.getAttribute(attrName);
                if (cd.isOnlyIfAbsent() && existing != null && !existing.isEmpty()) {
                    LOG.fine("Conditional default: attribute already set, skipping: @"
                            + attrName + "=" + existing);
                    continue;
                }
                elem.setAttribute(attrName, value);
                LOG.fine("Conditional default: set @" + attrName + "=" + value
                        + " on " + elem.getLocalName());
            }
        } else {
            // --- Element-text injection ---
            NodeList nodes = (NodeList) xpath.evaluate(
                    xpathExpr, document, XPathConstants.NODESET);
            if (nodes == null || nodes.getLength() == 0) {
                LOG.fine("Conditional default: no element nodes matched: " + xpathExpr);
                return;
            }
            for (int i = 0; i < nodes.getLength(); i++) {
                Element elem = (nodes.item(i) instanceof Element)
                        ? (Element) nodes.item(i) : null;
                if (elem == null) continue;
                String existing = elem.getTextContent();
                if (cd.isOnlyIfAbsent() && existing != null && !existing.trim().isEmpty()) {
                    LOG.fine("Conditional default: element already has text, skipping: "
                            + elem.getLocalName() + "=" + existing.trim());
                    continue;
                }
                while (elem.hasChildNodes())
                    elem.removeChild(elem.getFirstChild());
                elem.appendChild(document.createTextNode(value));
                LOG.fine("Conditional default: set text of " + elem.getLocalName()
                        + "=" + value);
            }
        }
    }

    /** Strips trailing XPath punctuation from an attribute/element name. */
    private static String cleanName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')
                sb.append(c);
            else
                break;
        }
        return sb.toString();
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

