// Copyright (C),2005-2024 HandCoded Software Ltd.
// All rights reserved.
//
// This software is licensed in accordance with the terms of the 'Open Source
// License (OSL) Version 3.0'. Please see 'license.txt' for the details.

package com.handcoded.fpml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and exposes the contents of a conversion delta profile XML file.
 *
 * <p>A delta profile is an optional XML document (conforming to
 * {@code conversion-delta.xsd}) that accompanies a conversion step and
 * provides:</p>
 * <ul>
 *   <li><b>helper-values</b> — default string values for structural
 *       conversions that require caller-supplied data (e.g. FX reference
 *       currencies for the 4.0 → 4.1 and 4.1 → 4.2 conversions).</li>
 *   <li><b>enrich</b> — a list of post-conversion DOM mutations (attribute
 *       sets, text replacements, attribute removals) described as XPath +
 *       value pairs and executed by {@link PostConversionEnricher}.</li>
 * </ul>
 *
 * <p>Instances are immutable once constructed.</p>
 *
 * @author Andrew Jacobs / ISDA FpML Team
 * @see    PostConversionEnricher
 * @see    ConfigurableHelper
 * @since  TFP 1.x
 */
public final class ConversionDeltaProfile {

    /** Namespace URI for conversion-delta documents. */
    public static final String NS = "http://www.handcoded.com/fpml/conversion-delta";

    // -------------------------------------------------------------------------
    // Factory / loading
    // -------------------------------------------------------------------------

    /**
     * Loads a {@link ConversionDeltaProfile} from the given {@link File}.
     *
     * @param  file  The profile XML file to read.
     * @return A fully-parsed {@link ConversionDeltaProfile}.
     * @throws IOException  If the file cannot be opened or read.
     * @throws SAXException If the file is not well-formed XML.
     */
    public static ConversionDeltaProfile load(File file) throws IOException, SAXException {
        try {
            DocumentBuilder builder = newBuilder();
            Document doc = builder.parse(file);
            return parse(doc);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("XML parser configuration error", e);
        }
    }

    /**
     * Loads a {@link ConversionDeltaProfile} from a classpath resource.
     *
     * @param  resourcePath  Classpath-relative path, e.g.
     *                       {@code "/com/handcoded/fpml/profiles/ird-5-12-5-13.xml"}.
     * @return A fully-parsed {@link ConversionDeltaProfile}.
     * @throws IOException  If the resource cannot be found or read.
     * @throws SAXException If the resource is not well-formed XML.
     */
    public static ConversionDeltaProfile loadFromClasspath(String resourcePath)
            throws IOException, SAXException {
        try (InputStream is = ConversionDeltaProfile.class.getResourceAsStream(resourcePath)) {
            if (is == null)
                throw new IOException("Classpath resource not found: " + resourcePath);
            DocumentBuilder builder = newBuilder();
            Document doc = builder.parse(is);
            return parse(doc);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("XML parser configuration error", e);
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return The source release version string, e.g. {@code "4-1"}. */
    public String getFromVersion()  { return fromVersion; }

    /** @return The target release version string, e.g. {@code "4-2"}. */
    public String getToVersion()    { return toVersion; }

    /**
     * @return The required source view (e.g. {@code "confirmation"}) or
     *         {@code null} if this profile matches any view.
     */
    public String getFromView()     { return fromView; }

    /**
     * @return The desired target view or {@code null} if the source view is
     *         preserved.
     */
    public String getToView()       { return toView; }

    /**
     * Returns the helper default value for the given key.
     *
     * @param  key  One of {@code referenceCurrency}, {@code quantoCurrency1},
     *              {@code quantoCurrency2}, {@code quantoCurrencyBasis}.
     * @return The configured default value, or {@code null} if absent.
     */
    public String getHelperValue(String key) {
        return helperValues.get(key);
    }

    /**
     * @return An unmodifiable ordered list of enrichment actions.  May be
     *         empty, never {@code null}.
     */
    public List<EnrichAction> getEnrichActions() {
        return enrichActions;
    }

    // -------------------------------------------------------------------------
    // EnrichAction — describes a single post-conversion DOM mutation
    // -------------------------------------------------------------------------

    /** The type of an enrichment action. */
    public enum ActionType { SET_ATTRIBUTE, REMOVE_ATTRIBUTE, SET_TEXT }

    /**
     * Describes a single DOM mutation to be applied to the converted document
     * by {@link PostConversionEnricher}.
     */
    public static final class EnrichAction {
        private final ActionType type;
        private final String     xpath;
        private final String     attribute;   // may be null
        private final String     value;       // may be null (for REMOVE_ATTRIBUTE)

        EnrichAction(ActionType type, String xpath, String attribute, String value) {
            this.type      = type;
            this.xpath     = xpath;
            this.attribute = attribute;
            this.value     = value;
        }

        /** @return The action type. */
        public ActionType getType()      { return type;      }
        /** @return The XPath expression that selects the target node(s). */
        public String     getXpath()     { return xpath;     }
        /** @return The attribute local name (may be {@code null}). */
        public String     getAttribute() { return attribute; }
        /** @return The string value to assign (may be {@code null} for REMOVE). */
        public String     getValue()     { return value;     }

        @Override
        public String toString() {
            return type + "(" + xpath + (attribute != null ? ", @" + attribute : "")
                    + (value != null ? "=" + value : "") + ")";
        }
    }

    // -------------------------------------------------------------------------
    // Private implementation
    // -------------------------------------------------------------------------

    private final String               fromVersion;
    private final String               toVersion;
    private final String               fromView;
    private final String               toView;
    private final Map<String, String>  helperValues;
    private final List<EnrichAction>   enrichActions;

    private ConversionDeltaProfile(String fromVersion, String toVersion,
                                   String fromView, String toView,
                                   Map<String, String> helperValues,
                                   List<EnrichAction> enrichActions) {
        this.fromVersion   = fromVersion;
        this.toVersion     = toVersion;
        this.fromView      = fromView;
        this.toView        = toView;
        this.helperValues  = Collections.unmodifiableMap(helperValues);
        this.enrichActions = Collections.unmodifiableList(enrichActions);
    }

    private static ConversionDeltaProfile parse(Document doc) {
        Element root = doc.getDocumentElement();
        String fromVersion = root.getAttribute("from");
        String toVersion   = root.getAttribute("to");
        String fromView    = nullIfEmpty(root.getAttribute("from-view"));
        String toView      = nullIfEmpty(root.getAttribute("to-view"));

        Map<String, String> helperValues  = new LinkedHashMap<String, String>();
        List<EnrichAction>  enrichActions = new ArrayList<EnrichAction>();

        // Parse <helper-values>
        NodeList hvList = root.getElementsByTagNameNS(NS, "helper-values");
        if (hvList.getLength() > 0) {
            Element hvElem = (Element) hvList.item(0);
            NodeList defaults = hvElem.getElementsByTagNameNS(NS, "default");
            for (int i = 0; i < defaults.getLength(); i++) {
                Element def = (Element) defaults.item(i);
                String key = def.getAttribute("key");
                String val = def.getTextContent().trim();
                if (key != null && !key.isEmpty())
                    helperValues.put(key, val);
            }
        }

        // Parse <enrich>
        NodeList enrichList = root.getElementsByTagNameNS(NS, "enrich");
        if (enrichList.getLength() > 0) {
            Element enrichElem = (Element) enrichList.item(0);
            org.w3c.dom.NodeList children = enrichElem.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                Element action = (Element) children.item(i);
                String localName = action.getLocalName();
                String xpath     = action.getAttribute("xpath");
                String attribute = nullIfEmpty(action.getAttribute("attribute"));
                String value     = nullIfEmpty(action.getAttribute("value"));

                if ("set-attribute".equals(localName))
                    enrichActions.add(new EnrichAction(ActionType.SET_ATTRIBUTE, xpath, attribute, value));
                else if ("remove-attribute".equals(localName))
                    enrichActions.add(new EnrichAction(ActionType.REMOVE_ATTRIBUTE, xpath, attribute, null));
                else if ("set-text".equals(localName))
                    enrichActions.add(new EnrichAction(ActionType.SET_TEXT, xpath, null, value));
            }
        }

        return new ConversionDeltaProfile(fromVersion, toVersion, fromView, toView,
                helperValues, enrichActions);
    }

    private static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder();
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}

