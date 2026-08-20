package com.spalimited.hotspotbilling.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading XML from somewhere that is not us.
 *
 * <p>Two features now parse XML they did not write — DPO's payment gateway and
 * the TR-069 ACS, which takes documents straight off customer premises equipment.
 * The second is worse than the first: a CPE is a cheap router in somebody's
 * living room, and a compromised one posting to this endpoint is not a
 * hypothetical.
 *
 * <p>An XML parser left at its defaults resolves entities a document declares. A
 * document naming {@code file:///etc/passwd} has it read off this server; one
 * naming a slow URL hangs the thread; one declaring nested entities expands to
 * gigabytes in memory. Disabling doctypes stops all three at once, and everything
 * else here is belt and braces for a parser that ignores that.
 *
 * <p>Extracted rather than copied when the second caller arrived. Hardening that
 * exists in two places is hardening that will be right in one of them.
 */
public final class SafeXml {

    /**
     * The largest document worth trying to parse.
     *
     * <p>A CWMP Inform from a busy CPE is a few kilobytes; a GetParameterValues
     * response listing an entire data model can be a couple of hundred. A
     * megabyte is far past anything legitimate, and refusing beyond it means a
     * device that streams forever costs a rejected request rather than a heap.
     */
    public static final int MAX_BYTES = 1_048_576;

    private SafeXml() {
    }

    /**
     * Parses a document that came from outside, or throws.
     *
     * <p>Throws rather than returning null so a caller cannot accidentally treat
     * a rejected document as an empty one — the difference between "this device
     * said nothing" and "this device said something we refused to read" matters
     * to everything downstream.
     */
    private static Document parse(byte[] xml, boolean namespaceAware) throws Exception {
        if (xml == null || xml.length == 0) {
            throw new IllegalArgumentException("empty document");
        }
        if (xml.length > MAX_BYTES) {
            throw new IllegalArgumentException("document is too large: " + xml.length + " bytes");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // The one that matters. With doctypes disallowed there is no way to
        // declare an entity, so external entities and billion-laughs both stop
        // here rather than at the four settings below.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        // Namespace-aware, because SOAP is: the same local name means different
        // things in different namespaces and matching on the prefix a device
        // happened to choose is how a parser gets confused by a legal document.
        factory.setNamespaceAware(namespaceAware);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    /**
     * Parses a document strictly, and then leniently if that failed.
     *
     * <p>For senders we do not control and cannot fix. A namespace-aware parser
     * rejects a document that uses a prefix it never declared -- {@code xsi:type}
     * with no {@code xmlns:xsi} is the common one -- and plenty of cheap consumer
     * hardware ships exactly that. Strictly speaking those devices are wrong.
     * Practically, refusing them means a router in somebody's house is silently
     * absent from the ACS with nobody knowing why, because a CPE does not report
     * an error anywhere a human will see.
     *
     * <p>So: strict first, because that is the reading that gets namespaces right
     * and namespaces are how SOAP tells one element from another. Lenient only as
     * the alternative to nothing. {@link #localName} already copes with the
     * prefix-only names the lenient pass produces.
     *
     * <p>The hardening is identical either way. Being forgiving about namespaces
     * is not being forgiving about doctypes.
     */
    public static Document parseLeniently(byte[] xml) throws Exception {
        try {
            return parse(xml, true);
        } catch (Exception strict) {
            return parse(xml, false);
        }
    }

    public static Document parse(byte[] xml) throws Exception {
        return parse(xml, true);
    }

    /**
     * The first element with this local name, in any namespace, anywhere.
     *
     * <p>Local name rather than prefix on purpose. A CPE may call the CWMP
     * namespace {@code cwmp:}, {@code soap-env:} or {@code ns1:} and all three
     * are legal — matching the prefix works until the first device that chose a
     * different one, which is a bug that only appears in the field.
     */
    public static Element first(Node scope, String localName) {
        if (scope == null) {
            return null;
        }
        NodeList children = scope instanceof Document doc
                ? doc.getElementsByTagName("*") : scope.getChildNodes();
        // getElementsByTagName("*") is a document-wide walk; for a subtree the
        // recursive search below is the equivalent.
        if (scope instanceof Document) {
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element && localName.equals(localName(element))) {
                    return element;
                }
            }
            return null;
        }
        return search(scope, localName);
    }

    private static Element search(Node scope, String localName) {
        NodeList children = scope.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                if (localName.equals(localName(element))) {
                    return element;
                }
                Element deeper = search(element, localName);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /** Every direct child element with this local name. */
    public static List<Element> children(Node scope, String localName) {
        List<Element> found = new ArrayList<>();
        if (scope == null) {
            return found;
        }
        NodeList children = scope.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(localName(element))) {
                found.add(element);
            }
        }
        return found;
    }

    /** The text of the first matching element, trimmed, or null. */
    public static String text(Node scope, String localName) {
        Element element = first(scope, localName);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** An element's own text, trimmed, or null. */
    public static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The local name, whatever prefix the sender chose. */
    public static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        // A document parsed without namespace awareness has no local name; take
        // whatever follows the colon.
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /** Escapes a value for putting into a document we are building. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        // Ampersand first, or it undoes the escaping the others just wrote.
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** Convenience for the common case of a UTF-8 string. */
    public static Document parse(String xml) throws Exception {
        return parse(xml == null ? null : xml.getBytes(StandardCharsets.UTF_8));
    }
}
