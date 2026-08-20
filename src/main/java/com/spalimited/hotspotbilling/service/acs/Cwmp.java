package com.spalimited.hotspotbilling.service.acs;

import com.spalimited.hotspotbilling.service.SafeXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The TR-069 wire format, read and written.
 *
 * <p>CWMP is SOAP 1.1 over HTTP with the conversation the wrong way round: the
 * CPE is the HTTP client and the ACS the server, but it is the ACS that gives
 * orders. A session goes CPE-posts-Inform, ACS-answers-InformResponse,
 * CPE-posts-nothing, ACS-answers-with-an-order, CPE-posts-the-result, and around
 * again until the ACS has nothing left and answers 204.
 *
 * <p>Built by hand rather than with a SOAP stack, and that is not laziness: the
 * envelopes are half a dozen shapes, hand-building them keeps the exact bytes
 * visible, and a JAX-WS stack would have to be added as a dependency to gain
 * nothing this does not already do.
 *
 * <h2>Three things that bite</h2>
 *
 * <p><b>The namespace version varies.</b> {@code cwmp-1-0} through
 * {@code cwmp-1-4} are all in the field, and a CPE that sent 1-0 expects 1-0
 * back. Answering in a different one is legal-looking and gets ignored, so the
 * version is read off the request and echoed.
 *
 * <p><b>The prefix is not the namespace.</b> Devices call it {@code cwmp:},
 * {@code ns1:}, {@code soap-env:} — all legal. Matching on the prefix works
 * until the first device that chose differently, which is a bug that only shows
 * up in somebody's living room.
 *
 * <p><b>An empty POST is a message.</b> It means "I have nothing more to say";
 * it is the CPE's cue for the ACS to give an order. Treating it as a malformed
 * request ends every session before anything useful happens.
 */
public final class Cwmp {

    public static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String DEFAULT_NS = "urn:dslforum-org:cwmp-1-0";

    private Cwmp() {
    }

    /** One parameter, as CWMP carries it. */
    public record Param(String name, String type, String value) {

        public static Param string(String name, String value) {
            return new Param(name, "xsd:string", value);
        }

        public static Param bool(String name, boolean value) {
            return new Param(name, "xsd:boolean", value ? "1" : "0");
        }

        public static Param integer(String name, long value) {
            return new Param(name, "xsd:unsignedInt", Long.toString(value));
        }
    }

    /** What a CPE just said. */
    public record Message(String type, String id, String namespace, Document document) {

        /** True for the empty POST that means "your turn". */
        public boolean isEmpty() {
            return type == null;
        }
    }

    /**
     * Reads whatever the CPE posted.
     *
     * <p>An empty body is a {@link Message} with no type rather than an error,
     * because it is the most common thing a CPE sends and it means something.
     */
    public static Message read(byte[] body) throws Exception {
        if (body == null || body.length == 0 || new String(body,
                java.nio.charset.StandardCharsets.UTF_8).isBlank()) {
            return new Message(null, null, DEFAULT_NS, null);
        }
        // Leniently, because the senders here are cheap routers in other
        // people's houses and a good many of them use xsi:type without ever
        // declaring the prefix. Refusing those is correct and useless.
        Document document = SafeXml.parseLeniently(body);
        Element bodyElement = SafeXml.first(document, "Body");
        String id = SafeXml.text(document, "ID");

        String type = null;
        String namespace = DEFAULT_NS;
        if (bodyElement != null) {
            for (Element child : elementsIn(bodyElement)) {
                type = SafeXml.localName(child);
                if (child.getNamespaceURI() != null && !child.getNamespaceURI().isBlank()) {
                    namespace = child.getNamespaceURI();
                }
                break;
            }
        }
        return new Message(type, id, namespace, document);
    }

    private static List<Element> elementsIn(Element parent) {
        List<Element> found = new ArrayList<>();
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) {
                found.add(element);
            }
        }
        return found;
    }

    /** What an Inform told us about the device and why it called. */
    public record Inform(String manufacturer, String oui, String productClass, String serial,
                         List<String> events, Map<String, String> parameters, int retryCount) {
    }

    /**
     * Pulls a device's identity and reason for calling out of an Inform.
     *
     * <p>The parameter list is kept whole rather than picked over: a CPE sends
     * whatever its {@code ParameterKey} list says, vendors differ on what that
     * includes, and the caller knows which of them it wants better than this does.
     */
    public static Inform readInform(Document document) {
        Element deviceId = SafeXml.first(document, "DeviceId");
        List<String> events = new ArrayList<>();
        Element eventElement = SafeXml.first(document, "Event");
        if (eventElement != null) {
            for (Element struct : SafeXml.children(eventElement, "EventStruct")) {
                String code = SafeXml.text(struct, "EventCode");
                if (code != null) {
                    events.add(code);
                }
            }
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        Element list = SafeXml.first(document, "ParameterList");
        if (list != null) {
            for (Element value : SafeXml.children(list, "ParameterValueStruct")) {
                String name = SafeXml.text(value, "Name");
                if (name != null) {
                    parameters.put(name, SafeXml.text(value, "Value"));
                }
            }
        }
        int retries = 0;
        String retryText = SafeXml.text(document, "RetryCount");
        if (retryText != null) {
            try {
                retries = Integer.parseInt(retryText.trim());
            } catch (NumberFormatException ignored) {
                // A device that sends nonsense here is still a device that called
                // in, and refusing the Inform over it would strand it forever.
            }
        }
        return new Inform(
                SafeXml.text(deviceId, "Manufacturer"),
                SafeXml.text(deviceId, "OUI"),
                SafeXml.text(deviceId, "ProductClass"),
                SafeXml.text(deviceId, "SerialNumber"),
                events, parameters, retries);
    }

    /** The values a GetParameterValuesResponse carried back. */
    public static Map<String, String> readParameterValues(Document document) {
        Map<String, String> values = new LinkedHashMap<>();
        Element list = SafeXml.first(document, "ParameterList");
        if (list == null) {
            return values;
        }
        for (Element value : SafeXml.children(list, "ParameterValueStruct")) {
            String name = SafeXml.text(value, "Name");
            if (name != null) {
                values.put(name, SafeXml.text(value, "Value"));
            }
        }
        return values;
    }

    /** A Fault the CPE returned instead of doing what it was told. */
    public record Fault(String code, String message) {
    }

    /** The fault in this message, if it is one. */
    public static Fault readFault(Document document) {
        Element fault = SafeXml.first(document, "Fault");
        if (fault == null) {
            return null;
        }
        // CWMP nests its own code inside the SOAP fault's detail; the SOAP-level
        // faultstring is usually just "CWMP fault" and says nothing useful.
        String code = SafeXml.text(fault, "FaultCode");
        String message = SafeXml.text(fault, "FaultString");
        if (code == null) {
            code = SafeXml.text(fault, "faultcode");
        }
        if (message == null) {
            message = SafeXml.text(fault, "faultstring");
        }
        return new Fault(code, message);
    }

    // ------------------------------------------------------------- writing

    private static String envelope(String namespace, String id, String bodyXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="%s" xmlns:cwmp="%s"\
                 xmlns:xsd="http://www.w3.org/2001/XMLSchema"\
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <soap:Header><cwmp:ID soap:mustUnderstand="1">%s</cwmp:ID></soap:Header>
                <soap:Body>%s</soap:Body>
                </soap:Envelope>"""
                .formatted(SOAP_ENV, namespace, SafeXml.escape(id == null ? "1" : id), bodyXml);
    }

    /**
     * The answer to an Inform.
     *
     * <p>MaxEnvelopes is 1 and always has been in practice: no CPE in the field
     * pipelines, and claiming otherwise invites one to try.
     */
    public static String informResponse(String namespace, String id) {
        return envelope(namespace, id,
                "<cwmp:InformResponse><MaxEnvelopes>1</MaxEnvelopes></cwmp:InformResponse>");
    }

    /** Asks the CPE for some values. */
    public static String getParameterValues(String namespace, String id, List<String> names) {
        StringBuilder body = new StringBuilder();
        body.append("<cwmp:GetParameterValues><ParameterNames soap:arrayType=\"xsd:string[")
                .append(names.size()).append("]\">");
        for (String name : names) {
            body.append("<string>").append(SafeXml.escape(name)).append("</string>");
        }
        body.append("</ParameterNames></cwmp:GetParameterValues>");
        return envelope(namespace, id, body.toString());
    }

    /**
     * Tells the CPE to change some values.
     *
     * <p>The ParameterKey is what a CPE quotes back in its next Inform to say
     * which change it has applied. Left blank it is legal and useless; set to the
     * task id it becomes the only way to tell "the device did what I asked" from
     * "the device rebooted for its own reasons".
     */
    public static String setParameterValues(String namespace, String id,
                                            List<Param> params, String parameterKey) {
        StringBuilder body = new StringBuilder();
        body.append("<cwmp:SetParameterValues><ParameterList soap:arrayType=")
                .append("\"cwmp:ParameterValueStruct[").append(params.size()).append("]\">");
        for (Param param : params) {
            body.append("<ParameterValueStruct><Name>").append(SafeXml.escape(param.name()))
                    .append("</Name><Value xsi:type=\"").append(param.type()).append("\">")
                    .append(SafeXml.escape(param.value()))
                    .append("</Value></ParameterValueStruct>");
        }
        body.append("</ParameterList><ParameterKey>")
                .append(SafeXml.escape(parameterKey == null ? "" : parameterKey))
                .append("</ParameterKey></cwmp:SetParameterValues>");
        return envelope(namespace, id, body.toString());
    }

    /** Reboots the CPE. CommandKey comes back on the next Inform. */
    public static String reboot(String namespace, String id, String commandKey) {
        return envelope(namespace, id, "<cwmp:Reboot><CommandKey>"
                + SafeXml.escape(commandKey == null ? "" : commandKey)
                + "</CommandKey></cwmp:Reboot>");
    }

    /** Wipes the CPE back to how it left the factory. */
    public static String factoryReset(String namespace, String id) {
        return envelope(namespace, id, "<cwmp:FactoryReset/>");
    }

    /** Tells the CPE to fetch and install firmware. FileType 1 is a firmware image. */
    public static String download(String namespace, String id, String commandKey,
                                  String url, String fileType, long fileSize) {
        return envelope(namespace, id, """
                <cwmp:Download><CommandKey>%s</CommandKey><FileType>%s</FileType>\
                <URL>%s</URL><Username></Username><Password></Password>\
                <FileSize>%d</FileSize><TargetFileName></TargetFileName>\
                <DelaySeconds>0</DelaySeconds><SuccessURL></SuccessURL>\
                <FailureURL></FailureURL></cwmp:Download>"""
                .formatted(SafeXml.escape(commandKey == null ? "" : commandKey),
                        SafeXml.escape(fileType == null ? "1 Firmware Upgrade Image" : fileType),
                        SafeXml.escape(url), Math.max(0, fileSize)));
    }
}
