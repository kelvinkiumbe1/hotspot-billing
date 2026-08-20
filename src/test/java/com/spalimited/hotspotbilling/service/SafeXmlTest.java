package com.spalimited.hotspotbilling.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hardening itself, asserted directly.
 *
 * <p>This file exists because a mutation run found the hole it fills. The ACS
 * test checked that a hostile Inform created no device, which passes both when
 * the document is rejected and when the entity merely goes unexpanded -- so
 * weakening the parser did not fail it. The protection has to be asserted at the
 * parser, not inferred from a side effect two layers up.
 */
class SafeXmlTest {

    private static final String XXE = """
            <?xml version="1.0"?>
            <!DOCTYPE root [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <root><value>&leak;</value></root>""";

    @Test
    @DisplayName("A doctype is refused outright, strictly")
    void strictRefusesADoctype() {
        assertThatThrownBy(() -> SafeXml.parse(XXE.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("A doctype is refused by the lenient pass too")
    void lenientAlsoRefusesADoctype() {
        // The lenient pass exists to forgive undeclared namespace prefixes, which
        // cheap CPEs send. Forgiving namespaces is not forgiving doctypes, and
        // this is the assertion that holds those two apart -- a mutation that
        // relaxed the doctype rule only on this path was otherwise undetected.
        assertThatThrownBy(() -> SafeXml.parseLeniently(XXE.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("A document past the size cap is refused before it is parsed")
    void oversizeIsRefused() {
        // A device that streams forever should cost a rejected request, not a heap.
        byte[] huge = new byte[SafeXml.MAX_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) 'x');

        assertThatThrownBy(() -> SafeXml.parseLeniently(huge))
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("An undeclared prefix is forgiven, which is the whole point")
    void undeclaredPrefixesSurviveTheLenientPass() {
        // xsi:type with no xmlns:xsi. Strictly wrong, and shipped by a great deal
        // of consumer hardware -- refusing it means a router is silently absent
        // from the ACS with nobody knowing why.
        String sloppy = "<root><value xsi:type=\"xsd:string\">hello</value></root>";

        assertThatThrownBy(() -> SafeXml.parse(sloppy.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
        assertThat(SafeXml.text(
                assertDoesNotThrowParse(sloppy), "value")).isEqualTo("hello");
    }

    private static org.w3c.dom.Document assertDoesNotThrowParse(String xml) {
        try {
            return SafeXml.parseLeniently(xml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("the lenient pass should have accepted this", e);
        }
    }

    @Test
    @DisplayName("Escaping does the ampersand first")
    void escapingOrder() {
        assertThat(SafeXml.escape("a&b")).isEqualTo("a&amp;b");
        assertThat(SafeXml.escape("a<b")).isEqualTo("a&lt;b");
        assertThat(SafeXml.escape("a&<b")).isEqualTo("a&amp;&lt;b");
        assertThat(SafeXml.escape(null)).isEmpty();
    }
}
