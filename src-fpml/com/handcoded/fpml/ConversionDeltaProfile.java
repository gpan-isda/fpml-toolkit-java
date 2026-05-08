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
     * Returns the name of the {@link com.handcoded.validation.RuleSet} to apply
     * to the <em>source</em> document before conversion (from the {@code <rules>}
     * section of the profile).
     *
     * @return The inbound rule-set name, or {@code null} if not declared.
     */
    public String getInboundRuleSetName()  { return inboundRuleSetName; }

    /**
     * Returns the name of the {@link com.handcoded.validation.RuleSet} to apply
     * to the <em>converted</em> document after enrichment (from the {@code <rules>}
     * section of the profile).
     *
     * @return The outbound rule-set name, or {@code null} if not declared.
     */
    public String getOutboundRuleSetName() { return outboundRuleSetName; }

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

    /**
     * @return An unmodifiable ordered list of conditional default injections.
     *         May be empty, never {@code null}.
     */
    public List<ConditionalDefault> getConditionalDefaults() {
        return conditionalDefaults;
    }

    /**
     * @return An unmodifiable ordered list of rename actions confirmed by a human
     *         reviewer.  May be empty, never {@code null}.
     */
    public List<RenameAction> getRenameActions() {
        return renameActions;
    }

    // -------------------------------------------------------------------------
    // RenameAction — a human-confirmed attribute/element rename between versions
    // -------------------------------------------------------------------------

    /**
     * Records a human-confirmed rename of an attribute or child element, produced
     * by {@link com.handcoded.meta.tools.DeltaProfileReviewer} when a
     * {@code REPLACED} entry is approved.
     *
     * <p><strong>Runtime support is planned but not yet implemented.</strong>
     * {@link PostConversionEnricher} currently logs and skips these entries.</p>
     */
    public static final class RenameAction {
        private final String from;
        private final String to;
        private final String xpath;  // may be null — parent-element XPath hint
        private final String note;   // may be null

        RenameAction(String from, String to, String xpath, String note) {
            this.from  = from;
            this.to    = to;
            this.xpath = xpath;
            this.note  = note;
        }

        /** @return The old attribute/element local name (source version). */
        public String getFrom()  { return from;  }
        /** @return The new attribute/element local name (target version). */
        public String getTo()    { return to;    }
        /** @return XPath hint for the parent element(s), or {@code null}. */
        public String getXpath() { return xpath; }
        /** @return Free-text reviewer note, or {@code null}. */
        public String getNote()  { return note;  }

        @Override
        public String toString() {
            return "RENAME(" + from + " → " + to
                    + (xpath != null ? ", xpath=" + xpath : "") + ")";
        }
    }

    // -------------------------------------------------------------------------
    // EnrichAction — describes a single post-conversion DOM mutation
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // ConditionalDefault — describes a scoped, guarded default-value injection
    // -------------------------------------------------------------------------

    /**
     * Describes a single default-value injection to be applied to the converted
     * document by {@link PostConversionEnricher}.
     *
     * <p>An injection is "conditional" because it is only applied when:</p>
     * <ul>
     *   <li>The target node is absent (or empty) in the converted document — when
     *       {@code onlyIfAbsent} is {@code true} (the default).</li>
     *   <li>The document's detected FpML view matches {@code view} (if set).</li>
     *   <li>The document contains a product element matching {@code productType}
     *       (if set).</li>
     * </ul>
     */
    public static final class ConditionalDefault {
        private final String  xpath;
        private final String  value;
        private final boolean onlyIfAbsent;
        private final String  view;             // null = any view
        private final String  productType;      // null = any product
        private final String  constraintType;   // informational: schema-required, etc.
        private final String  note;             // reviewer free-text
        private final boolean promptAtRuntime;  // prompt operator if value is blank

        ConditionalDefault(String xpath, String value, boolean onlyIfAbsent,
                           String view, String productType,
                           String constraintType, String note,
                           boolean promptAtRuntime) {
            this.xpath           = xpath;
            this.value           = value;
            this.onlyIfAbsent    = onlyIfAbsent;
            this.view            = view;
            this.productType     = productType;
            this.constraintType  = constraintType;
            this.note            = note;
            this.promptAtRuntime = promptAtRuntime;
        }

        /** @return XPath 1.0 expression selecting the target attribute or element. */
        public String  getXpath()           { return xpath; }
        /** @return The static default value to inject (may be empty when promptAtRuntime). */
        public String  getValue()           { return value; }
        /** @return Whether to skip injection when the node already has a value. */
        public boolean isOnlyIfAbsent()     { return onlyIfAbsent; }
        /** @return Required FpML view (e.g. "confirmation"), or {@code null} for any. */
        public String  getView()            { return view; }
        /** @return Required product-type local name, or {@code null} for any. */
        public String  getProductType()     { return productType; }
        /** @return Informational constraint tag (schema-required, …). */
        public String  getConstraintType()  { return constraintType; }
        /** @return Reviewer note. */
        public String  getNote()            { return note; }
        /**
         * @return {@code true} when the conversion runtime should prompt the
         *         operator for a value rather than silently skipping this entry.
         *         Requires a {@link RuntimeValueProvider} on the pipeline.
         */
        public boolean isPromptAtRuntime()  { return promptAtRuntime; }

        @Override
        public String toString() {
            return "ConditionalDefault{xpath=" + xpath + ", value=" + value
                    + (promptAtRuntime ? ", promptAtRuntime=true" : "")
                    + (view != null ? ", view=" + view : "")
                    + (productType != null ? ", productType=" + productType : "")
                    + ", onlyIfAbsent=" + onlyIfAbsent + "}";
        }
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
    private final String               inboundRuleSetName;
    private final String               outboundRuleSetName;
    private final Map<String, String>  helperValues;
    private final List<EnrichAction>   enrichActions;
    private final List<ConditionalDefault> conditionalDefaults;
    private final List<RenameAction>   renameActions;

    private ConversionDeltaProfile(String fromVersion, String toVersion,
                                   String fromView, String toView,
                                   String inboundRuleSetName, String outboundRuleSetName,
                                   Map<String, String> helperValues,
                                   List<EnrichAction> enrichActions,
                                   List<ConditionalDefault> conditionalDefaults,
                                   List<RenameAction> renameActions) {
        this.fromVersion         = fromVersion;
        this.toVersion           = toVersion;
        this.fromView            = fromView;
        this.toView              = toView;
        this.inboundRuleSetName  = inboundRuleSetName;
        this.outboundRuleSetName = outboundRuleSetName;
        this.helperValues        = Collections.unmodifiableMap(helperValues);
        this.enrichActions       = Collections.unmodifiableList(enrichActions);
        this.conditionalDefaults = Collections.unmodifiableList(conditionalDefaults);
        this.renameActions       = Collections.unmodifiableList(renameActions);
    }

    private static ConversionDeltaProfile parse(Document doc) {
        Element root = doc.getDocumentElement();
        String fromVersion = root.getAttribute("from");
        String toVersion   = root.getAttribute("to");
        String fromView    = nullIfEmpty(root.getAttribute("from-view"));
        String toView      = nullIfEmpty(root.getAttribute("to-view"));

        Map<String, String> helperValues      = new LinkedHashMap<String, String>();
        List<EnrichAction>  enrichActions     = new ArrayList<EnrichAction>();
        List<ConditionalDefault> conditionalDefaults = new ArrayList<ConditionalDefault>();
        List<RenameAction>  renameActions     = new ArrayList<RenameAction>();
        String inboundRuleSetName  = null;
        String outboundRuleSetName = null;

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

        // Parse <rules>
        NodeList rulesList = root.getElementsByTagNameNS(NS, "rules");
        if (rulesList.getLength() > 0) {
            Element rulesElem = (Element) rulesList.item(0);
            NodeList inboundList = rulesElem.getElementsByTagNameNS(NS, "inbound");
            if (inboundList.getLength() > 0)
                inboundRuleSetName = nullIfEmpty(((Element) inboundList.item(0)).getAttribute("name"));
            NodeList outboundList = rulesElem.getElementsByTagNameNS(NS, "outbound");
            if (outboundList.getLength() > 0)
                outboundRuleSetName = nullIfEmpty(((Element) outboundList.item(0)).getAttribute("name"));
        }

        // Parse <conditional-defaults>
        NodeList cdList = root.getElementsByTagNameNS(NS, "conditional-defaults");
        if (cdList.getLength() > 0) {
            Element cdElem = (Element) cdList.item(0);
            NodeList insertList = cdElem.getElementsByTagNameNS(NS, "insert");
            for (int i = 0; i < insertList.getLength(); i++) {
                Element ins = (Element) insertList.item(i);
                String  xpath              = ins.getAttribute("xpath");
                String  value              = ins.getAttribute("value");
                String  onlyIfAbsentStr    = ins.getAttribute("only-if-absent");
                boolean onlyIfAbsent       = onlyIfAbsentStr.isEmpty()
                        || "true".equalsIgnoreCase(onlyIfAbsentStr);
                String  view               = nullIfEmpty(ins.getAttribute("view"));
                String  productType        = nullIfEmpty(ins.getAttribute("product-type"));
                String  constraintType     = nullIfEmpty(ins.getAttribute("constraint-type"));
                String  note               = nullIfEmpty(ins.getAttribute("note"));
                boolean promptAtRuntime    =
                        "true".equalsIgnoreCase(ins.getAttribute("prompt-at-runtime"));
                if (xpath != null && !xpath.isEmpty())
                    conditionalDefaults.add(new ConditionalDefault(xpath, value,
                            onlyIfAbsent, view, productType, constraintType, note,
                            promptAtRuntime));
            }

            // Parse <rename> entries inside <conditional-defaults>
            NodeList renameList = cdElem.getElementsByTagNameNS(NS, "rename");
            for (int i = 0; i < renameList.getLength(); i++) {
                Element ren  = (Element) renameList.item(i);
                String  from = ren.getAttribute("from");
                String  to   = ren.getAttribute("to");
                String  xpath = nullIfEmpty(ren.getAttribute("xpath"));
                String  note  = nullIfEmpty(ren.getAttribute("note"));
                if (from != null && !from.isEmpty() && to != null && !to.isEmpty())
                    renameActions.add(new RenameAction(from, to, xpath, note));
            }
        }

        return new ConversionDeltaProfile(fromVersion, toVersion, fromView, toView,
                inboundRuleSetName, outboundRuleSetName, helperValues, enrichActions,
                conditionalDefaults, renameActions);
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

