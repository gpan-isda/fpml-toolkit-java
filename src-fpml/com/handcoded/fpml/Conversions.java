package com.handcoded.fpml;

import java.util.ArrayList;
import java.util.Hashtable;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.w3c.dom.TypeInfo;

import com.handcoded.meta.ConversionException;
import com.handcoded.meta.DirectConversion;
import com.handcoded.meta.Helper;
import com.handcoded.meta.Schema;
import com.handcoded.xml.DOM;
import com.handcoded.xml.XPath;

/**
 * Instances to migrate FpML documents between releases of the specification.
 * Currently only conversions from earlier releases to later ones are supported.
 *
 * This version ensures all output elements are created in the target document's namespace.
 *
 * @author Andrew Jacobs
 * @since TFP 1.0
 */
public final class Conversions {

    /** Ensures no instances can be constructed. */
    private Conversions() { }

    /* =================================================================================================
     * Generic DOM copier that forces the target namespace
     * ================================================================================================= */

    /**
     * Copy a source subtree into target, recreating elements that belong to the given source namespace
     * so they use the target namespace instead. Elements in other namespaces are imported as-is.
     *
     * @param src           The source node to copy
     * @param targetDoc     The target document
     * @param parentInTarget Parent under which the copy is appended
     * @param sourceNs      Namespace that should be replaced (e.g., old FpML ns)
     * @param targetNs      Namespace to use for recreated elements (e.g., new FpML ns)
     */
    private static void copyToTargetNs(Node src,
                                       Document targetDoc,
                                       Node parentInTarget,
                                       String sourceNs,
                                       String targetNs) {
        switch (src.getNodeType()) {
            case Node.ELEMENT_NODE: {
                Element s = (Element) src;
                String sNs = s.getNamespaceURI();    // may be null
                String local = s.getLocalName();     // may be null
                String qName = (local != null) ? local : s.getTagName(); // avoid carrying old prefixes

                // Decide whether we need to recreate in target namespace
                final boolean replaceNs = (sourceNs != null && sourceNs.equals(sNs));
                Element d = replaceNs
                        ? targetDoc.createElementNS(targetNs, qName)
                        : (sNs != null
                        ? targetDoc.createElementNS(sNs, qName)
                        : targetDoc.createElement(qName));

                // Copy attributes (preserve their namespaces/prefixes exactly)
                NamedNodeMap attrs = s.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    Attr a = (Attr) attrs.item(i);
                    String ans = a.getNamespaceURI();
                    String aname = (a.getPrefix() != null && a.getLocalName() != null)
                            ? a.getPrefix() + ":" + a.getLocalName()
                            : a.getName();
                    if (ans != null) {
                        d.setAttributeNS(ans, aname, a.getValue());
                    } else {
                        d.setAttribute(aname, a.getValue());
                    }
                }

                parentInTarget.appendChild(d);

                // Recurse children
                for (Node c = s.getFirstChild(); c != null; c = c.getNextSibling()) {
                    copyToTargetNs(c, targetDoc, d, sourceNs, targetNs);
                }
                break;
            }
            default:
                // Text, CDATA, Comment, PI: import directly
                parentInTarget.appendChild(targetDoc.importNode(src, true));
        }
    }

    /**
     * Convenience: replace-only behavior. We only recreate elements that are in sourceNs;
     * elements that belong to other namespaces are imported without change.
     */
    private static void copyReplaceNamespace(Node src,
                                             Document targetDoc,
                                             Node parentInTarget,
                                             String sourceNsToReplace) {
        String targetNs = (targetDoc.getDocumentElement() != null)
                ? targetDoc.getDocumentElement().getNamespaceURI()
                : null;
        copyToTargetNs(src, targetDoc, parentInTarget, sourceNsToReplace, targetNs);
    }

    /* =================================================================================================
     * Shared helpers
     * ================================================================================================= */

    /**
     * Append a boolean leaf element with text content "true"/"false".
     * @since TFP 1.7
     */
    private static void append(Element parent, String localName, boolean value) {
        // Use parent's namespace (target) to stay consistent
        String ns = parent.getNamespaceURI();
        Element child = (ns != null)
                ? parent.getOwnerDocument().createElementNS(ns, localName)
                : parent.getOwnerDocument().createElement(localName);
        Text text = parent.getOwnerDocument().createTextNode(value ? "true" : "false");
        parent.appendChild(child);
        child.appendChild(text);
    }

    /**
     * Helper to create a target Document for a conversion, wrapping runtime errors
     * from SchemaRelease.newInstance into a ConversionException with context.
     */
    private static Document createTargetDocument(com.handcoded.meta.Release targetRelease, String rootName)
    {
        StringBuilder errors = new StringBuilder();
        if (targetRelease == null)
            throw new RuntimeException(new com.handcoded.meta.ConversionException("Target release is null"));

        if (rootName != null) {
            try {
                return targetRelease.newInstance(rootName);
            }
            catch (RuntimeException re) { errors.append("Tried root '").append(rootName).append(" -> ").append(re.getMessage()).append("; "); }
        }

        String[] roots = targetRelease.getRootElements();
        if (roots != null) {
            // Prefer DataDocument-like names first
            for (String r : roots) {
                if (r.equalsIgnoreCase("DataDocument") || r.equalsIgnoreCase("dataDocument")) {
                    try { return targetRelease.newInstance(r); }
                    catch (RuntimeException re) { errors.append("Tried root '").append(r).append(" -> ").append(re.getMessage()).append("; "); }
                }
            }
            // Then prefer 'FpML'
            for (String r : roots) {
                if ("FpML".equals(r)) {
                    try { return targetRelease.newInstance(r); }
                    catch (RuntimeException re) { errors.append("Tried root '").append(r).append(" -> ").append(re.getMessage()).append("; "); }
                }
            }
            // Finally any declared root
            for (String r : roots) {
                try { return targetRelease.newInstance(r); }
                catch (RuntimeException re) { errors.append("Tried root '").append(r).append(" -> ").append(re.getMessage()).append("; "); }
            }
        }

        // Wrap checked ConversionException into an unchecked exception so callers that cannot
        // declare the checked exception (e.g. some generated classes) still compile.
        throw new RuntimeException(new com.handcoded.meta.ConversionException(
                "Failed to create target document for conversion to release " + targetRelease.getVersion()
                        + "; attempts: " + errors.toString()));
    }

    /**
     * Create a target document resolving the root name from the source document.
     * Copies the old document's xsi:type if present and not already set on the target.
     */
    private static Document createTargetDocument(com.handcoded.meta.Release targetRelease, Document source)
            throws com.handcoded.meta.ConversionException {
        Element oldRoot = (source == null) ? null : source.getDocumentElement();
        String resolved = resolveTargetRootName(targetRelease, oldRoot);
        Document target = createTargetDocument(targetRelease, resolved);
        Element newRoot = (target == null) ? null : target.getDocumentElement();
        String oldType = (oldRoot == null) ? null : oldRoot.getAttributeNS(Schema.INSTANCE_URL, "type");
        String newType = (newRoot == null) ? null : newRoot.getAttributeNS(Schema.INSTANCE_URL, "type");
        if (oldType != null && oldType.length() > 0 && (newType == null || newType.length() == 0) && newRoot != null)
            newRoot.setAttributeNS(Schema.INSTANCE_URL, "xsi:type", oldType);
        return target;
    }

    /**
     * Resolve the best root element name to use when creating a target Document for a conversion.
     */
    private static String resolveTargetRootName(com.handcoded.meta.Release targetRelease, Element oldRoot) {
        if (targetRelease == null) return "FpML";
        String oldName = (oldRoot == null) ? null : oldRoot.getLocalName();

        // Preserve old non-wrapper root if target knows it; avoid literal 'FpML' when possible
        if (oldName != null && !"FpML".equals(oldName) && targetRelease.hasRootElement(oldName))
            return oldName;

        String[] roots = targetRelease.getRootElements();
        if (roots != null) {
            for (String r : roots)
                if (r.equalsIgnoreCase("DataDocument") || r.equalsIgnoreCase("dataDocument")) return r;
            for (String r : roots)
                if ("FpML".equals(r)) return "FpML";
            if (roots.length > 0) return roots[0];
        }
        return "FpML";
    }

    /* =================================================================================================
     * Conversions
     * ================================================================================================= */

    /* -------------------------------------------------------------------------------------------------
     * R1_0 -> R2_0 (SPECIAL CASES preserved; but we only enforce target namespace for copies)
     * ------------------------------------------------------------------------------------------------- */
    public static class R1_0__R2_0 extends DirectConversion {
        public R1_0__R2_0() { super(Releases.R1_0, Releases.R2_0); }

        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Document target = createTargetDocument(getTargetRelease(), "FpML");
            Element oldRoot = source.getDocumentElement();
            Element newRoot = target.getDocumentElement();
            NamedNodeMap attrs = oldRoot.getAttributes();

            // Transfer the scheme default attributes
            for (int index = 0; index < attrs.getLength(); ++index) {
                Attr attr = (Attr) attrs.item(index);
                if (attr.getName().endsWith("SchemeDefault")) {
                    String name = attr.getName();
                    String value = attr.getValue();
                    if (Releases.R1_0.getSchemeDefaults().getDefaultUriForAttribute(name).equals(value))
                        value = Releases.R2_0.getSchemeDefaults().getDefaultUriForAttribute(name);
                    if (value != null) newRoot.setAttributeNS(null, name, value);
                }
            }

            String sourceNs = oldRoot.getNamespaceURI(); // the namespace we want to replace
            String targetNs = newRoot.getNamespaceURI(); // the target schema namespace
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling()) {
                // Recreate only elements that are in oldRoot's namespace, using targetNs
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            }
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R2_0 -> R3_0 (SPECIAL CASES preserved; namespace-safe element creation and attribute transforms)
     * ------------------------------------------------------------------------------------------------- */
    public static class R2_0__R3_0 extends DirectConversion {
        public R2_0__R3_0() { super(Releases.R2_0, Releases.R3_0); }

        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Document target = createTargetDocument(getTargetRelease(), source);
            Element oldRoot = source.getDocumentElement();
            Element newRoot = target.getDocumentElement();
            NamedNodeMap attrs = oldRoot.getAttributes();

            // Transfer the scheme default attributes
            for (int index = 0; index < attrs.getLength(); ++index) {
                Attr attr = (Attr) attrs.item(index);
                if (attr.getName().endsWith("SchemeDefault")) {
                    String name = attr.getName();
                    String value = attr.getValue();
                    if (Releases.R2_0.getSchemeDefaults().getDefaultUriForAttribute(name).equals(value))
                        value = Releases.R3_0.getSchemeDefaults().getDefaultUriForAttribute(name);
                    if (value != null) newRoot.setAttributeNS(null, name, value);
                }
            }

            // Transcribe each of the first level child elements with transforms
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                transcribe(node, target, newRoot, new ArrayList<Element>());

            // Then append saved party elements (collected during transform pass)
            // (No saved parties in this simplified approach—collection is inside 'transcribe' if needed)
            return target;
        }

        /** Recursively copies the structure, preserving transforms; all new elements use target namespace. */
        private void transcribe(Node context, Document document, Node parent, ArrayList<Element> parties) {
            switch (context.getNodeType()) {
                case Node.ELEMENT_NODE: {
                    Element element = (Element) context;

                    // First pass — save party elements if needed (kept for compatibility; not used further here)
                    if ((parties != null) && "party".equals(element.getNodeName())) {
                        parties.add(element);
                        return;
                    }

                    // Create element in TARGET namespace
                    String targetNs = parent.getNamespaceURI();
                    String qn = (element.getLocalName() != null) ? element.getLocalName() : element.getTagName();
                    Element clone = (targetNs != null) ? document.createElementNS(targetNs, qn)
                            : document.createElement(qn);

                    parent.appendChild(clone);

                    // Transfer and update attributes (href + scheme defaults)
                    NamedNodeMap attrs = element.getAttributes();
                    for (int index = 0; index < attrs.getLength(); ++index) {
                        Attr attr = (Attr) attrs.item(index);
                        String name = attr.getName();
                        if (!("type".equals(name) || "base".equals(name))) {
                            String value = attr.getValue();
                            if ("href".equals(name)) {
                                if (value.startsWith("#")) value = value.substring(1);
                            } else if (name.endsWith("Scheme")) {
                                String oldDefault = Releases.R2_0.getSchemeDefaults().getDefaultAttributeForScheme(name);
                                String newDefault = Releases.R3_0.getSchemeDefaults().getDefaultAttributeForScheme(name);
                                if (oldDefault != null && newDefault != null) {
                                    String defaultUri = Releases.R2_0.getSchemeDefaults().getDefaultUriForAttribute(oldDefault);
                                    if ((defaultUri != null) && defaultUri.equals(value))
                                        value = Releases.R3_0.getSchemeDefaults().getDefaultUriForAttribute(newDefault);
                                }
                            }
                            if (value != null) {
                                String ans = attr.getNamespaceURI();
                                String aname = (attr.getPrefix() != null && attr.getLocalName() != null)
                                        ? (attr.getPrefix() + ":" + attr.getLocalName())
                                        : attr.getName();
                                if (ans != null) clone.setAttributeNS(ans, aname, value);
                                else clone.setAttribute(aname, value);
                            }
                        }
                    }

                    // Recursively copy child nodes (switch namespace for FpML elements)
                    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling())
                        copyReplaceNamespace(node, document, clone, element.getNamespaceURI());
                    break;
                }
                default:
                    parent.appendChild(document.importNode(context, true));
            }
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R3_0 -> R4_0 (SPECIAL CASES preserved; now target-namespace aware; cache widened)
     * ------------------------------------------------------------------------------------------------- */
    public static class R3_0__R4_0 extends DirectConversion {
        public R3_0__R4_0() { super(Releases.R3_0, Releases.R4_0); }

        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Document target = createTargetDocument(getTargetRelease(), source);
            Element oldRoot = source.getDocumentElement();
            Element newRoot = target.getDocumentElement();

            // Cache widened to Object (holds Elements and Strings)
            Hashtable<String, Object> cache = new Hashtable<>();
            newRoot.setAttributeNS(Schema.INSTANCE_URL, "xsi:type", "DataDocument");

            String ns = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                transcribe(node, target, newRoot, cache, true, ns);

            return target;
        }

        /** Full special-case logic preserved; all created elements use target namespace. */
        private void transcribe(Node context, Document document, Node parent,
                                Hashtable<String, Object> cache, boolean caching, String targetNamespace) {
            switch (context.getNodeType()) {
                case Node.ELEMENT_NODE: {
                    Element element = (Element) context;
                    Element clone;

                    // Cache the element
                    if (caching && "calculationAgentPartyReference".equals(element.getLocalName())
                            && "tradeHeader".equals(element.getParentNode().getLocalName())) {
                        cache.put("calculationAgentPartyReference", element);
                        return;
                    }

                    // EquityOption components
                    if ("buyerParty".equals(element.getLocalName())) {
                        clone = document.createElementNS(targetNamespace, "buyerPartyReference");
                        clone.setAttribute("href", DOM.getElementByLocalName(element, "partyReference").getAttribute("href"));
                        parent.appendChild(clone);
                        break;
                    }
                    if ("sellerParty".equals(element.getLocalName())) {
                        clone = document.createElementNS(targetNamespace, "sellerPartyReference");
                        clone.setAttribute("href", DOM.getElementByLocalName(element, "partyReference").getAttribute("href"));
                        parent.appendChild(clone);
                        break;
                    }
                    if ("underlying".equals(element.getLocalName())) {
                        clone = document.createElementNS(targetNamespace, "underlyer");
                        Element singleUnderlyer = document.createElementNS(targetNamespace, "singleUnderlyer");
                        Element underlyingAsset;
                        if (element.getElementsByTagName("extraordinaryEvents").getLength() == 0)
                            underlyingAsset = document.createElementNS(targetNamespace, "index");
                        else
                            underlyingAsset = document.createElementNS(targetNamespace, "equity");

                        NodeList list = element.getElementsByTagName("instrumentId");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, underlyingAsset, element.getNamespaceURI());

                        Element description = document.createElementNS(targetNamespace, "description");
                        DOM.setInnerText(description, DOM.getInnerText(XPath.path(element, "description")));
                        underlyingAsset.appendChild(description);

                        Element optional;
                        if ((optional = XPath.path(element, "currency")) != null)
                            copyReplaceNamespace(optional, document, underlyingAsset, element.getNamespaceURI());
                        if ((optional = XPath.path(element, "exchangeId")) != null)
                            copyReplaceNamespace(optional, document, underlyingAsset, element.getNamespaceURI());
                        if ((optional = XPath.path(element, "clearanceSystem")) != null)
                            copyReplaceNamespace(optional, document, underlyingAsset, element.getNamespaceURI());

                        singleUnderlyer.appendChild(underlyingAsset);
                        clone.appendChild(singleUnderlyer);
                        parent.appendChild(clone);
                        break;
                    }
                    if ("settlementDate".equals(element.getLocalName())) {
                        clone = document.createElementNS(targetNamespace, "settlementDate");
                        Element relativeDate = document.createElementNS(targetNamespace, "relativeDate");
                        NodeList list = element.getChildNodes();
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, relativeDate, element.getNamespaceURI());
                        clone.appendChild(relativeDate);
                        parent.appendChild(clone);
                        break;
                    }

                    // FX components
                    if ("fixing".equals(element.getLocalName())) {
                        clone = document.createElementNS(targetNamespace, "fixing");
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "primaryRateSource")) != null)
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "secondaryRateSource")) != null)
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fixingTime")) != null)
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "quotedCurrencyPair")) != null)
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fixingDate")) != null)
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        parent.appendChild(clone);
                        break;
                    }

                    // Renames
                    if ("informationSource".equals(element.getLocalName())
                            && "fxSpotRateSource".equals(element.getParentNode().getLocalName())) {
                        clone = document.createElementNS(targetNamespace, "primaryRateSource");
                    } else {
                        String qn2 = (element.getLocalName() != null) ? element.getLocalName() : element.getTagName();
                        clone = document.createElementNS(targetNamespace, qn2);
                    }

                    // Generate calculationAgent before peers
                    if ("calculationAgentBusinessCenter".equals(element.getLocalName())
                            || "governingLaw".equals(element.getLocalName())
                            || "documentation".equals(element.getLocalName())) {
                        Element agent = (Element) cache.get("calculationAgentPartyReference");
                        if (agent != null) {
                            Element container = document.createElementNS(targetNamespace, "calculationAgent");
                            clone.appendChild(container);
                            copyReplaceNamespace(agent, document, parent, agent.getNamespaceURI());
                            cache.remove("calculationAgentPartyReference");
                        }
                    }

                    parent.appendChild(clone);

                    // fraDiscounting value change
                    if ("fraDiscounting".equals(element.getLocalName())) {
                        if (DOM.getInnerText(element).trim().equals("true"))
                            DOM.setInnerText(clone, "ISDA");
                        else
                            DOM.setInnerText(clone, "NONE");
                        break;
                    }

                    // Capitalisation changes
                    if ("quoteBasis".equals(element.getLocalName())) {
                        String value = DOM.getInnerText(element).trim().toUpperCase();
                        if (value.equals("CURRENCY1PERCURRENCY2")) DOM.setInnerText(clone, "Currency1PerCurrency2");
                        else if (value.equals("CURRENCY2PERCURRENCY1")) DOM.setInnerText(clone, "Currency2PerCurrency1");
                        else DOM.setInnerText(clone, DOM.getInnerText(element).trim());
                        break;
                    }
                    if ("sideRateBasis".equals(element.getLocalName())) {
                        String value = DOM.getInnerText(element).trim().toUpperCase();
                        if (value.equals("CURRENCY1PERBASECURRENCY")) DOM.setInnerText(clone, "Currency1PerBaseCurrency");
                        else if (value.equals("BASECURRENCYPERCURRENCY1")) DOM.setInnerText(clone, "BaseCurrencyPerCurrency1");
                        else if (value.equals("CURRENCY2PERBASECURRENCY")) DOM.setInnerText(clone, "Currency2PerBaseCurrency");
                        else if (value.equals("BASECURRENCYPERCURRENCY2")) DOM.setInnerText(clone, "BaseCurrencyPerCurrency2");
                        else DOM.setInnerText(clone, DOM.getInnerText(element).trim());
                        break;
                    }
                    if ("premiumQuoteBasis".equals(element.getLocalName())) {
                        String value = DOM.getInnerText(element).trim().toUpperCase();
                        if (value.equals("PERCENTAGEOFCALLCURRENCYAMOUNT")) DOM.setInnerText(clone, "PercentageOfCallCurrencyAmount");
                        else if (value.equals("PERCENTAGEOFPUTCURRENCYAMOUNT")) DOM.setInnerText(clone, "PercentageOfPutCurrencyAmount");
                        else if (value.equals("CALLCURRENCYPERPUTCURRENCY")) DOM.setInnerText(clone, "CallCurrencyPerPutCurrency");
                        else if (value.equals("PUTCURRENCYPERCALLCURRENCY")) DOM.setInnerText(clone, "PutCurrencyPerCallCurrency");
                        else if (value.equals("EXPLICIT")) DOM.setInnerText(clone, "Explicit");
                        else DOM.setInnerText(clone, DOM.getInnerText(element).trim());
                        break;
                    }
                    if ("strikeQuoteBasis".equals(element.getLocalName()) || "averageRateQuoteBasis".equals(element.getLocalName())) {
                        String value = DOM.getInnerText(element).trim().toUpperCase();
                        if (value.equals("CALLCURRENCYPERPUTCURRENCY")) DOM.setInnerText(clone, "CallCurrencyPerPutCurrency");
                        else if (value.equals("PUTCURRENCYPERCALLCURRENCY")) DOM.setInnerText(clone, "PutCurrencyPerCallCurrency");
                        else DOM.setInnerText(clone, DOM.getInnerText(element).trim());
                        break;
                    }
                    if ("fxBarrierType".equals(element.getLocalName())) {
                        String value = DOM.getInnerText(element).trim().toUpperCase();
                        if (value.equals("KNOCKIN")) DOM.setInnerText(clone, "Knockin");
                        else if (value.equals("KNOCKOUT")) DOM.setInnerText(clone, "Knockout");
                        else if (value.equals("REVERSEKNOCKIN")) DOM.setInnerText(clone, "ReverseKnockin");
                        else if (value.equals("REVERSEKNOCKOUT")) DOM.setInnerText(clone, "ReverseKnockout");
                        else DOM.setInnerText(clone, DOM.getInnerText(element).trim());
                        break;
                    }

                    // Elements changed from schemes to enumerations
                    if ("optionType".equals(element.getLocalName())
                            || "nationalisationOrInsolvency".equals(element.getLocalName())
                            || "delisting".equals(element.getLocalName())) {
                        DOM.setInnerText(clone, DOM.getInnerText(element).trim());
                        break;
                    }

                    // Copy attributes (filter type/base)
                    NamedNodeMap attrs = element.getAttributes();
                    for (int index = 0; index < attrs.getLength(); ++index) {
                        Attr attr = (Attr) attrs.item(index);
                        String name = attr.getLocalName();
                        if (!("type".equals(name) || "base".equals(name))) clone.setAttribute(attr.getName(), attr.getValue());
                    }

                    if ("mandatoryEarlyTermination".equals(element.getLocalName())) {
                        cache.put("dateRelativeToId", element.getAttribute("id")); // store as String
                        clone.removeAttribute("id");
                    }
                    if ("mandatoryEarlyTerminationDate".equals(element.getLocalName())) {
                        clone.setAttribute("id", (String) cache.get("dateRelativeToId"));
                    }

                    // Fix href on cash settlement dateRelativeTo
                    if ("dateRelativeTo".equals(element.getLocalName())) {
                        if ("cashSettlementValuationDate".equals(element.getParentNode().getLocalName())) {
                            if (!"mandatoryEarlyTermination".equals(
                                    element.getParentNode().getParentNode().getParentNode().getLocalName())) {
                                String id;
                                for (int count = 1;;) {
                                    id = "AutoRef" + (count++);
                                    if (document.getElementById(id) == null) break;
                                }
                                cache.put("dateRelativeToId", id);
                            }
                            clone.setAttribute("href", (String) cache.get("dateRelativeToId"));
                        }
                        break;
                    }
                    if ("cashSettlementPaymentDate".equals(element.getLocalName())) {
                        clone.setAttribute("id", (String) cache.get("dateRelativeToId"));
                    }

                    // Recursively copy children across (namespace replace)
                    NodeList list = element.getChildNodes();
                    for (int index = 0; index < list.getLength(); ++index)
                        transcribe(list.item(index), document, clone, cache, caching, targetNamespace);

                    // Generate calculationAgent at end of trade if no peer element
                    if ("trade".equals(element.getLocalName())) {
                        Element agent = (Element) cache.get("calculationAgentPartyReference");
                        if (agent != null) {
                            Element container = document.createElementNS(targetNamespace, "calculationAgent");
                            clone.appendChild(container);
                            copyReplaceNamespace(agent, document, container, agent.getNamespaceURI());
                            cache.remove("calculationAgentPartyReference");
                        }
                    }

                    break;
                }
                default:
                    copyReplaceNamespace(context, document, parent, parent.getNamespaceURI());
            }
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_0 -> R4_1 (SPECIAL CASES preserved; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_0__R4_1 extends DirectConversion {
        public R4_0__R4_1() { super(Releases.R4_0, Releases.R4_1); }

        public static interface Helper extends com.handcoded.meta.Helper {
            String getReferenceCurrency(final Element context);
            String getQuantoCurrency1(final Element context);
            String getQuantoCurrency2(final Element context);
            String getQuantoCurrencyBasis(final Element context);
        }

        @Override
        public Document convert(Document source, com.handcoded.meta.Helper helper) throws ConversionException {
            Document target = createTargetDocument(getTargetRelease(), source);
            Element oldRoot = source.getDocumentElement();
            Element newRoot = target.getDocumentElement();

            // Transfer the message type
            newRoot.setAttributeNS(Schema.INSTANCE_URL, "xsi:type", oldRoot.getAttributeNS(Schema.INSTANCE_URL, "type"));

            String ns = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                transcribe(node, target, newRoot, helper, ns);

            return target;
        }

        private void transcribe(Node context, Document document, Node parent,
                                com.handcoded.meta.Helper helper, String targetNamespace) throws ConversionException {
            switch (context.getNodeType()) {
                case Node.ELEMENT_NODE: {
                    Element element = (Element) context;
                    Element clone;

                    // Ignore failureToDeliverApplicable
                    if ("failureToDeliverApplicable".equals(element.getLocalName())) break;

                    // Renames
                    if ("equityOptionFeatures".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "equityFeatures");
                    else if ("automaticExerciseApplicable".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "automaticExercise");
                    else if ("equityBermudanExercise".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "equityBermudaExercise");
                    else if ("bermudanExerciseDates".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "bermudaExerciseDates");
                    else if ("fxSource".equals(element.getLocalName()) || "fxDetermination".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "fxSpotRateSource");
                    else if ("futuresPriceValuationApplicable".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "futuresPriceValuation");
                    else if ("equityValuationDate".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "valuationDate");
                    else if ("equityValuationDates".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "valuationDates");
                    else if ("fxTerms".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "fxFeature");
                    else clone = document.createElementNS(targetNamespace, (element.getLocalName() != null) ? element.getLocalName() : element.getTagName());

                    parent.appendChild(clone);

                    // clearanceSystem rename
                    if ("clearanceSystem".equals(element.getLocalName())) {
                        clone.setAttribute("clearanceSystemScheme", element.getAttribute("clearanceSystemIdScheme"));
                        DOM.setInnerText(clone, DOM.getInnerText(element));
                        break;
                    }
                    // routingId rename
                    if ("routingId".equals(element.getLocalName())) {
                        clone.setAttribute("routingIdCodeScheme", element.getAttribute("routingIdScheme"));
                        DOM.setInnerText(clone, DOM.getInnerText(element));
                        break;
                    }

                    // Copy all attributes
                    NamedNodeMap attrs = element.getAttributes();
                    for (int index = 0; index < attrs.getLength(); ++index) {
                        Attr attr = (Attr) attrs.item(index);
                        clone.setAttribute(attr.getName(), attr.getValue());
                    }

                    // Restructure equityOption
                    if ("equityOption".equals(element.getLocalName())) {
                        Element targetEl;
                        Element premium = document.createElementNS(targetNamespace, "equityPremium");
                        Element payer = document.createElementNS(targetNamespace, "payerPartyReference");
                        Element receiver = document.createElementNS(targetNamespace, "receiverPartyReference");
                        if ((targetEl = XPath.path(element, "buyerPartyReference")) != null) {
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                            payer.setAttribute("href", targetEl.getAttribute("href"));
                        }
                        if ((targetEl = XPath.path(element, "sellerPartyReference")) != null) {
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                            receiver.setAttribute("href", targetEl.getAttribute("href"));
                        }
                        if ((targetEl = XPath.path(element, "optionType")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "equityEffectiveDate")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "underlyer")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "notional")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "equityExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fxFeature")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "methodOfAdjustment")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "extraordinaryEvents")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        else {
                            Element child = document.createElementNS(targetNamespace, "extraordinaryEvents");
                            Element failure = document.createElementNS(targetNamespace, "failureToDeliver");
                            if ((targetEl = XPath.path(element, "equityExercise", "failureToDeliverApplicable")) != null)
                                DOM.setInnerText(failure, DOM.getInnerText(targetEl));
                            else DOM.setInnerText(failure, "false");
                            child.appendChild(failure);
                            clone.appendChild(child);
                        }
                        if ((targetEl = XPath.path(element, "equityOptionFeatures")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "strike")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "spot")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "numberOfOptions")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "optionEntitlement")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        premium.appendChild(payer);
                        premium.appendChild(receiver);
                        clone.appendChild(premium);
                        break;
                    }

                    // Restructure swaption
                    if ("swaption".equals(element.getLocalName())) {
                        NodeList list;
                        Element targetEl;
                        Element agent = document.createElementNS(targetNamespace, "calculationAgent");
                        if ((targetEl = XPath.path(element, "buyerPartyReference")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "sellerPartyReference")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("premium");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "americanExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "bermudaExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "europeanExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "exerciseProcedure")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        clone.appendChild(agent);
                        list = element.getElementsByTagName("calculationAgentPartyReference");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, agent, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "cashSettlement")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "swaptionStraddle")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "swaptionAdjustedDates")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "swap")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Restructure fxFeature
                    if ("fxFeature".equals(element.getLocalName())) {
                        Element child;
                        Element targetEl;
                        Element rccy = document.createElementNS(targetNamespace, "referenceCurrency");
                        if (helper instanceof R4_0__R4_1.Helper) {
                            DOM.setInnerText(rccy, ((R4_0__R4_1.Helper) helper).getReferenceCurrency(element));
                            clone.appendChild(rccy);
                        } else throw new ConversionException("Cannot determine the fxFeature reference currency");

                        if (DOM.getInnerText(XPath.path(element, "fxFeatureType")).trim().toUpperCase().equals("COMPOSITE")) {
                            child = document.createElementNS(targetNamespace, "composite");
                            if ((targetEl = XPath.path(element, "fxSource")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                        } else {
                            child = document.createElementNS(targetNamespace, "quanto");
                            Element pair = document.createElementNS(targetNamespace, "quotedCurrencyPair");
                            Element ccy1 = document.createElementNS(targetNamespace, "currency1");
                            Element ccy2 = document.createElementNS(targetNamespace, "currency2");
                            Element basis = document.createElementNS(targetNamespace, "quoteBasis");
                            Element rate = document.createElementNS(targetNamespace, "fxRate");
                            Element value = document.createElementNS(targetNamespace, "rate");
                            if (helper instanceof R4_0__R4_1.Helper) {
                                DOM.setInnerText(ccy1, ((R4_0__R4_1.Helper) helper).getQuantoCurrency1(element));
                                DOM.setInnerText(ccy2, ((R4_0__R4_1.Helper) helper).getQuantoCurrency2(element));
                                DOM.setInnerText(basis, ((R4_0__R4_1.Helper) helper).getQuantoCurrencyBasis(element));
                                pair.appendChild(ccy1);
                                pair.appendChild(ccy2);
                                pair.appendChild(basis);
                            } else throw new ConversionException("Cannot determine fxFeature quanto currencies");
                            if ((targetEl = XPath.path(element, "fxRate")) != null) DOM.setInnerText(value, DOM.getInnerText(targetEl));
                            else DOM.setInnerText(value, "0.0000");
                            rate.appendChild(pair);
                            rate.appendChild(value);
                            child.appendChild(rate);
                            if ((targetEl = XPath.path(element, "fxSource")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                        }
                        clone.appendChild(child);
                        break;
                    }

                    // Restructure fxTerms
                    if ("fxTerms".equals(element.getLocalName())) {
                        Element kind;
                        Element child;
                        if ((kind = XPath.path(element, "quanto")) != null) {
                            copyReplaceNamespace(XPath.path(kind, "referenceCurrency"), document, clone, element.getNamespaceURI());
                            child = document.createElementNS(targetNamespace, "quanto");
                            NodeList list = kind.getElementsByTagName("fxRate");
                            for (int index = 0; index < list.getLength(); ++index)
                                copyReplaceNamespace(list.item(index), document, child, element.getNamespaceURI());
                            clone.appendChild(child);
                        }
                        if ((kind = XPath.path(element, "compositeFx")) != null) {
                            Element targetEl;
                            copyReplaceNamespace(XPath.path(kind, "referenceCurrency"), document, clone, element.getNamespaceURI());
                            child = document.createElementNS(targetNamespace, "composite");
                            if ((targetEl = XPath.path(kind, "determinationMethod")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                            if ((targetEl = XPath.path(kind, "relativeDate")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                            if ((targetEl = XPath.path(kind, "fxDetermination")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                            clone.appendChild(child);
                        }
                        break;
                    }

                    // Equity swap buyer/seller references
                    if ("equitySwap".equals(element.getLocalName())) {
                        NodeList list;
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "productType")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("productId");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        Element firstLeg = (Element) element.getElementsByTagName("equityLeg").item(0);
                        Element buyer = document.createElementNS(targetNamespace, "buyerPartyReference");
                        Element seller = document.createElementNS(targetNamespace, "sellerPartyReference");
                        buyer.setAttribute("href", XPath.path(firstLeg, "payerPartyReference").getAttribute("href"));
                        seller.setAttribute("href", XPath.path(firstLeg, "receiverPartyReference").getAttribute("href"));
                        clone.appendChild(buyer);
                        clone.appendChild(seller);
                        list = element.getElementsByTagName("equityLeg");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("interestLeg");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "principalExchangeFeatures")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("additionalPayment");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("earlyTermination");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Restructure initialPrice / valuationPriceFinal
                    if ("initialPrice".equals(element.getLocalName()) || "valuationPriceFinal".equals(element.getLocalName())) {
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "commission")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "determinationMethod")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "amountRelativeTo")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "grossPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "netPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "accruedInterestPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fxConversion")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        Element valuation = document.createElementNS(targetNamespace, "equityValuation");
                        if ((targetEl = XPath.path(element, "equityValuationDate")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTimeType")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTime")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        clone.appendChild(valuation);
                        break;
                    }

                    // Restructure valuationPriceInterim
                    if ("valuationPriceInterim".equals(element.getLocalName())) {
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "commission")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "determinationMethod")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "amountRelativeTo")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "grossPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "netPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "accruedInterestPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fxConversion")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        Element valuation = document.createElementNS(targetNamespace, "equityValuation");
                        if ((targetEl = XPath.path(element, "equityValuationDates")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTimeType")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTime")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        clone.appendChild(valuation);
                        break;
                    }

                    // New optionality in constituentWeight
                    if ("constituentWeight".equals(element.getLocalName())) {
                        Element targetEl = XPath.path(element, "basketPercentage");
                        if (targetEl != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        else copyReplaceNamespace(XPath.path(element, "openUnits"), document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Transfer failureToDeliver into extraordinaryEvents
                    if ("extraordinaryEvents".equals(element.getLocalName())) {
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "mergerEvents")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        Element failure = document.createElementNS(targetNamespace, "failureToDeliver");
                        if ((targetEl = XPath.path(element, "..", "equityExercise", "failureToDeliverApplicable")) != null)
                            DOM.setInnerText(failure, DOM.getInnerText(targetEl));
                        else DOM.setInnerText(failure, "false");
                        clone.appendChild(failure);
                        if ((targetEl = XPath.path(element, "nationalisationOrInsolvency")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "delisting")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Recurse
                    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling())
                        transcribe(node, document, clone, helper, targetNamespace);

                    break;
                }
                default:
                    copyReplaceNamespace(context, document, parent, parent.getNamespaceURI());
            }
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_1 -> R4_2 (SPECIAL CASES preserved; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_1__R4_2 extends DirectConversion {
        public R4_1__R4_2() { super(Releases.R4_1, Releases.R4_2); }

        public static interface Helper extends com.handcoded.meta.Helper {
            String getReferenceCurrency(final Element context);
            String getQuantoCurrency1(final Element context);
            String getQuantoCurrency2(final Element context);
            String getQuantoCurrencyBasis(final Element context);
        }

        @Override
        public Document convert(Document source, com.handcoded.meta.Helper helper) throws ConversionException {
            Document target = createTargetDocument(getTargetRelease(), source);
            Element oldRoot = source.getDocumentElement();
            Element newRoot = target.getDocumentElement();

            // Transfer the message type
            newRoot.setAttributeNS(Schema.INSTANCE_URL, "xsi:type", oldRoot.getAttributeNS(Schema.INSTANCE_URL, "type"));

            String ns = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                transcribe(node, target, newRoot, helper, ns);

            return target;
        }

        private void transcribe(Node context, Document document, Node parent,
                                com.handcoded.meta.Helper helper, String targetNamespace) throws ConversionException {
            switch (context.getNodeType()) {
                case Node.ELEMENT_NODE: {
                    Element element = (Element) context;
                    Element clone;

                    // Ignore failureToDeliverApplicable
                    if ("failureToDeliverApplicable".equals(element.getLocalName())) break;

                    // Renames
                    if ("equityOptionFeatures".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "equityFeatures");
                    else if ("automaticExerciseApplicable".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "automaticExercise");
                    else if ("equityBermudanExercise".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "equityBermudaExercise");
                    else if ("bermudanExerciseDates".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "bermudaExerciseDates");
                    else if ("fxSource".equals(element.getLocalName()) || "fxDetermination".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "fxSpotRateSource");
                    else if ("futuresPriceValuationApplicable".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "futuresPriceValuation");
                    else if ("equityValuationDate".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "valuationDate");
                    else if ("equityValuationDates".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "valuationDates");
                    else if ("fxTerms".equals(element.getLocalName())) clone = document.createElementNS(targetNamespace, "fxFeature");
                    else clone = document.createElementNS(targetNamespace, (element.getLocalName() != null) ? element.getLocalName() : element.getTagName());

                    parent.appendChild(clone);

                    // clearanceSystem rename
                    if ("clearanceSystem".equals(element.getLocalName())) {
                        clone.setAttribute("clearanceSystemScheme", element.getAttribute("clearanceSystemIdScheme"));
                        DOM.setInnerText(clone, DOM.getInnerText(element));
                        break;
                    }
                    // routingId rename
                    if ("routingId".equals(element.getLocalName())) {
                        clone.setAttribute("routingIdCodeScheme", element.getAttribute("routingIdScheme"));
                        DOM.setInnerText(clone, DOM.getInnerText(element));
                        break;
                    }

                    // Copy all attributes
                    NamedNodeMap attrs = element.getAttributes();
                    for (int index = 0; index < attrs.getLength(); ++index) {
                        Attr attr = (Attr) attrs.item(index);
                        clone.setAttribute(attr.getName(), attr.getValue());
                    }

                    // Restructure equityOption
                    if ("equityOption".equals(element.getLocalName())) {
                        Element targetEl;
                        Element premium = document.createElementNS(targetNamespace, "equityPremium");
                        Element payer = document.createElementNS(targetNamespace, "payerPartyReference");
                        Element receiver = document.createElementNS(targetNamespace, "receiverPartyReference");
                        if ((targetEl = XPath.path(element, "buyerPartyReference")) != null) {
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                            payer.setAttribute("href", targetEl.getAttribute("href"));
                        }
                        if ((targetEl = XPath.path(element, "sellerPartyReference")) != null) {
                            copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                            receiver.setAttribute("href", targetEl.getAttribute("href"));
                        }
                        if ((targetEl = XPath.path(element, "optionType")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "equityEffectiveDate")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "underlyer")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "notional")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "equityExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fxFeature")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "methodOfAdjustment")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "extraordinaryEvents")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        else {
                            Element child = document.createElementNS(targetNamespace, "extraordinaryEvents");
                            Element failure = document.createElementNS(targetNamespace, "failureToDeliver");
                            if ((targetEl = XPath.path(element, "equityExercise", "failureToDeliverApplicable")) != null)
                                DOM.setInnerText(failure, DOM.getInnerText(targetEl));
                            else DOM.setInnerText(failure, "false");
                            child.appendChild(failure);
                            clone.appendChild(child);
                        }
                        if ((targetEl = XPath.path(element, "equityOptionFeatures")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "strike")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "spot")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "numberOfOptions")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "optionEntitlement")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        premium.appendChild(payer);
                        premium.appendChild(receiver);
                        clone.appendChild(premium);
                        break;
                    }

                    // Restructure swaption
                    if ("swaption".equals(element.getLocalName())) {
                        NodeList list;
                        Element targetEl;
                        Element agent = document.createElementNS(targetNamespace, "calculationAgent");
                        if ((targetEl = XPath.path(element, "buyerPartyReference")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "sellerPartyReference")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("premium");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "americanExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "bermudaExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "europeanExercise")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "exerciseProcedure")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        clone.appendChild(agent);
                        list = element.getElementsByTagName("calculationAgentPartyReference");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, agent, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "cashSettlement")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "swaptionStraddle")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "swaptionAdjustedDates")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "swap")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Restructure fxFeature
                    if ("fxFeature".equals(element.getLocalName())) {
                        Element child;
                        Element targetEl;
                        Element rccy = document.createElementNS(targetNamespace, "referenceCurrency");
                        if (helper instanceof R4_1__R4_2.Helper) {
                            DOM.setInnerText(rccy, ((R4_1__R4_2.Helper) helper).getReferenceCurrency(element));
                            clone.appendChild(rccy);
                        } else throw new ConversionException("Cannot determine the fxFeature reference currency");
                        if (DOM.getInnerText(XPath.path(element, "fxFeatureType")).trim().toUpperCase().equals("COMPOSITE")) {
                            child = document.createElementNS(targetNamespace, "composite");
                            if ((targetEl = XPath.path(element, "fxSource")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                        } else {
                            child = document.createElementNS(targetNamespace, "quanto");
                            Element pair = document.createElementNS(targetNamespace, "quotedCurrencyPair");
                            Element ccy1 = document.createElementNS(targetNamespace, "currency1");
                            Element ccy2 = document.createElementNS(targetNamespace, "currency2");
                            Element basis = document.createElementNS(targetNamespace, "quoteBasis");
                            Element rate = document.createElementNS(targetNamespace, "fxRate");
                            Element value = document.createElementNS(targetNamespace, "rate");
                            if (helper instanceof R4_1__R4_2.Helper) {
                                DOM.setInnerText(ccy1, ((R4_1__R4_2.Helper) helper).getQuantoCurrency1(element));
                                DOM.setInnerText(ccy2, ((R4_1__R4_2.Helper) helper).getQuantoCurrency2(element));
                                DOM.setInnerText(basis, ((R4_1__R4_2.Helper) helper).getQuantoCurrencyBasis(element));
                                pair.appendChild(ccy1);
                                pair.appendChild(ccy2);
                                pair.appendChild(basis);
                            } else throw new ConversionException("Cannot determine fxFeature quanto currencies");
                            if ((targetEl = XPath.path(element, "fxRate")) != null) DOM.setInnerText(value, DOM.getInnerText(targetEl));
                            else DOM.setInnerText(value, "0.0000");
                            rate.appendChild(pair);
                            rate.appendChild(value);
                            child.appendChild(rate);
                            if ((targetEl = XPath.path(element, "fxSource")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                        }
                        clone.appendChild(child);
                        break;
                    }

                    // Restructure fxTerms
                    if ("fxTerms".equals(element.getLocalName())) {
                        Element kind;
                        Element child;
                        if ((kind = XPath.path(element, "quanto")) != null) {
                            copyReplaceNamespace(XPath.path(kind, "referenceCurrency"), document, clone, element.getNamespaceURI());
                            child = document.createElementNS(targetNamespace, "quanto");
                            NodeList list = kind.getElementsByTagName("fxRate");
                            for (int index = 0; index < list.getLength(); ++index)
                                copyReplaceNamespace(list.item(index), document, child, element.getNamespaceURI());
                            clone.appendChild(child);
                        }
                        if ((kind = XPath.path(element, "compositeFx")) != null) {
                            Element targetEl;
                            copyReplaceNamespace(XPath.path(kind, "referenceCurrency"), document, clone, element.getNamespaceURI());
                            child = document.createElementNS(targetNamespace, "composite");
                            if ((targetEl = XPath.path(kind, "determinationMethod")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                            if ((targetEl = XPath.path(kind, "relativeDate")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                            if ((targetEl = XPath.path(kind, "fxDetermination")) != null) copyReplaceNamespace(targetEl, document, child, element.getNamespaceURI());
                            clone.appendChild(child);
                        }
                        break;
                    }

                    // Equity swap buyer/seller references
                    if ("equitySwap".equals(element.getLocalName())) {
                        NodeList list;
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "productType")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("productId");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        Element firstLeg = (Element) element.getElementsByTagName("equityLeg").item(0);
                        Element buyer = document.createElementNS(targetNamespace, "buyerPartyReference");
                        Element seller = document.createElementNS(targetNamespace, "sellerPartyReference");
                        buyer.setAttribute("href", XPath.path(firstLeg, "payerPartyReference").getAttribute("href"));
                        seller.setAttribute("href", XPath.path(firstLeg, "receiverPartyReference").getAttribute("href"));
                        clone.appendChild(buyer);
                        clone.appendChild(seller);
                        list = element.getElementsByTagName("equityLeg");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("interestLeg");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "principalExchangeFeatures")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("additionalPayment");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        list = element.getElementsByTagName("earlyTermination");
                        for (int index = 0; index < list.getLength(); ++index)
                            copyReplaceNamespace(list.item(index), document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Restructure initialPrice / valuationPriceFinal
                    if ("initialPrice".equals(element.getLocalName()) || "valuationPriceFinal".equals(element.getLocalName())) {
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "commission")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "determinationMethod")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "amountRelativeTo")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "grossPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "netPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "accruedInterestPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fxConversion")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        Element valuation = document.createElementNS(targetNamespace, "equityValuation");
                        if ((targetEl = XPath.path(element, "equityValuationDate")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTimeType")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTime")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        clone.appendChild(valuation);
                        break;
                    }

                    // Restructure valuationPriceInterim
                    if ("valuationPriceInterim".equals(element.getLocalName())) {
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "commission")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "determinationMethod")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "amountRelativeTo")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "grossPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "netPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "accruedInterestPrice")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "fxConversion")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        Element valuation = document.createElementNS(targetNamespace, "equityValuation");
                        if ((targetEl = XPath.path(element, "equityValuationDates")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTimeType")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "valuationTime")) != null) copyReplaceNamespace(targetEl, document, valuation, element.getNamespaceURI());
                        clone.appendChild(valuation);
                        break;
                    }

                    // New optionality in constituentWeight
                    if ("constituentWeight".equals(element.getLocalName())) {
                        Element targetEl = XPath.path(element, "basketPercentage");
                        if (targetEl != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        else copyReplaceNamespace(XPath.path(element, "openUnits"), document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Transfer failureToDeliver into extraordinaryEvents
                    if ("extraordinaryEvents".equals(element.getLocalName())) {
                        Element targetEl;
                        if ((targetEl = XPath.path(element, "mergerEvents")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        Element failure = document.createElementNS(targetNamespace, "failureToDeliver");
                        if ((targetEl = XPath.path(element, "..", "equityExercise", "failureToDeliverApplicable")) != null)
                            DOM.setInnerText(failure, DOM.getInnerText(targetEl));
                        else DOM.setInnerText(failure, "false");
                        clone.appendChild(failure);
                        if ((targetEl = XPath.path(element, "nationalisationOrInsolvency")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        if ((targetEl = XPath.path(element, "delisting")) != null) copyReplaceNamespace(targetEl, document, clone, element.getNamespaceURI());
                        break;
                    }

                    // Recurse
                    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling())
                        transcribe(node, document, clone, helper, targetNamespace);

                    break;
                }
                default:
                    copyReplaceNamespace(context, document, parent, parent.getNamespaceURI());
            }
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_2 -> R4_3 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_2__R4_3 extends DirectConversion {
        public R4_2__R4_3() { super(Releases.R4_2, Releases.R4_3); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_3 -> R4_4 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_3__R4_4 extends DirectConversion {
        public R4_3__R4_4() { super(Releases.R4_3, Releases.R4_4); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_4 -> R4_5 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_4__R4_5 extends DirectConversion {
        public R4_4__R4_5() { super(Releases.R4_4, Releases.R4_5); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_5 -> R4_6 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_5__R4_6 extends DirectConversion {
        public R4_5__R4_6() { super(Releases.R4_5, Releases.R4_6); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_6 -> R4_7 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_6__R4_7 extends DirectConversion {
        public R4_6__R4_7() { super(Releases.R4_6, Releases.R4_7); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_7 -> R4_8 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_7__R4_8 extends DirectConversion {
        public R4_7__R4_8() { super(Releases.R4_7, Releases.R4_8); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_8 -> R4_9 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_8__R4_9 extends DirectConversion {
        public R4_8__R4_9() { super(Releases.R4_8, Releases.R4_9); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R4_9 -> R4_10 (DIRECT COPY; target namespace enforced)
     * ------------------------------------------------------------------------------------------------- */
    public static class R4_9__R4_10 extends DirectConversion {
        public R4_9__R4_10() { super(Releases.R4_9, Releases.R4_10); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    /* -------------------------------------------------------------------------------------------------
     * R5.x Confirmations/Reportings — pass-through with target namespace enforced
     * ------------------------------------------------------------------------------------------------- */

    public static class R5_8__R5_9_CONFIRMATION extends DirectConversion {
        public R5_8__R5_9_CONFIRMATION() { super(Releases.R5_8_CONFIRMATION, Releases.R5_9_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R4_10__R5_0_CONFIRMATION extends DirectConversion {
        public R4_10__R5_0_CONFIRMATION() { super(Releases.R4_10, Releases.R5_0_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), source);
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_0__R5_1_CONFIRMATION extends DirectConversion {
        public R5_0__R5_1_CONFIRMATION() { super(Releases.R5_0_CONFIRMATION, Releases.R5_1_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), source);
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_0__R5_1_REPORTING extends DirectConversion {
        public R5_0__R5_1_REPORTING() { super(Releases.R5_0_REPORTING, Releases.R5_1_REPORTING); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), source);
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_1__R5_2_CONFIRMATION extends DirectConversion {
        public R5_1__R5_2_CONFIRMATION() { super(Releases.R5_1_CONFIRMATION, Releases.R5_2_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_2__R5_3_CONFIRMATION extends DirectConversion {
        public R5_2__R5_3_CONFIRMATION() { super(Releases.R5_2_CONFIRMATION, Releases.R5_3_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_3__R5_4_CONFIRMATION extends DirectConversion {
        public R5_3__R5_4_CONFIRMATION() { super(Releases.R5_3_CONFIRMATION, Releases.R5_4_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_4__R5_5_CONFIRMATION extends DirectConversion {
        public R5_4__R5_5_CONFIRMATION() { super(Releases.R5_4_CONFIRMATION, Releases.R5_5_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_5__R5_6_CONFIRMATION extends DirectConversion {
        public R5_5__R5_6_CONFIRMATION() { super(Releases.R5_5_CONFIRMATION, Releases.R5_6_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            String resolvedRoot = resolveTargetRootName(getTargetRelease(), oldRoot);
            Document target = createTargetDocument(getTargetRelease(), resolvedRoot);
            Element newRoot = target.getDocumentElement();
            String oldType = (oldRoot == null) ? null : oldRoot.getAttributeNS(Schema.INSTANCE_URL, "type");
            String newType = (newRoot == null) ? null : newRoot.getAttributeNS(Schema.INSTANCE_URL, "type");
            if (oldType != null && oldType.length() > 0 && (newType == null || newType.length() == 0))
                newRoot.setAttributeNS(Schema.INSTANCE_URL, "xsi:type", oldType);
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_6__R5_7_CONFIRMATION extends DirectConversion {
        public R5_6__R5_7_CONFIRMATION() { super(Releases.R5_6_CONFIRMATION, Releases.R5_7_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_7__R5_8_CONFIRMATION extends DirectConversion {
        public R5_7__R5_8_CONFIRMATION() { super(Releases.R5_7_CONFIRMATION, Releases.R5_8_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(), oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_9__R5_10_CONFIRMATION extends DirectConversion {
        public R5_9__R5_10_CONFIRMATION() { super(Releases.R5_9_CONFIRMATION, Releases.R5_10_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_10__R5_11_CONFIRMATION extends DirectConversion {
        public R5_10__R5_11_CONFIRMATION() { super(Releases.R5_10_CONFIRMATION, Releases.R5_11_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_11__R5_12_CONFIRMATION extends DirectConversion {
        public R5_11__R5_12_CONFIRMATION() { super(Releases.R5_11_CONFIRMATION, Releases.R5_12_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }

    public static class R5_12__R5_13_CONFIRMATION extends DirectConversion {
        public R5_12__R5_13_CONFIRMATION() { super(Releases.R5_12_CONFIRMATION, Releases.R5_13_CONFIRMATION); }
        @Override
        public Document convert(Document source, Helper helper) throws ConversionException {
            Element oldRoot = source.getDocumentElement();
            Document target = createTargetDocument(getTargetRelease(),
                    oldRoot == null ? null : oldRoot.getLocalName());
            Element newRoot = target.getDocumentElement();
            String sourceNs = oldRoot.getNamespaceURI();
            String targetNs = newRoot.getNamespaceURI();
            for (Node node = oldRoot.getFirstChild(); node != null; node = node.getNextSibling())
                copyToTargetNs(node, target, newRoot, sourceNs, targetNs);
            return target;
        }
    }
}
