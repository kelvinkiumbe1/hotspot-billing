package com.spalimited.hotspotbilling.service.calls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document that decides what happens to a live call.
 *
 * <p>Worth testing to the character, because there is no second attempt. The
 * provider posts once, our reply is the whole instruction, and a malformed
 * document is a customer listening to silence with nothing in any log to explain
 * it. This is also the only half of the phone line that can be proved without an
 * account, a rented number and real money.
 */
class VoiceXmlTest {

    /** Parsed rather than string-matched: valid XML is the actual requirement. */
    private static void assertWellFormed(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("Not well-formed XML:\n" + xml, e);
        }
    }

    @Test
    @DisplayName("dialling one number produces a greeting and a Dial")
    void dialOne() {
        String xml = VoiceXml.dial(List.of("+254700000001"), "Please hold.", false, 25);

        assertWellFormed(xml);
        assertThat(xml).contains("<Say>Please hold.</Say>");
        assertThat(xml).contains("phoneNumbers=\"+254700000001\"");
        assertThat(xml).doesNotContain("record=");
    }

    @Test
    @DisplayName("several agents are rung one after another, not all at once")
    void dialsSequentially() {
        String xml = VoiceXml.dial(List.of("+254700000001", "+254700000002"), null, false, 25);

        assertWellFormed(xml);
        assertThat(xml).contains("phoneNumbers=\"+254700000001,+254700000002\"");
        // Ringing every agent for one caller means three people drop what they
        // are doing and two find a dead line, which teaches them to ignore it.
        assertThat(xml).contains("sequential=\"true\"");
    }

    @Test
    @DisplayName("no greeting means no Say element at all")
    void noGreeting() {
        String xml = VoiceXml.dial(List.of("+254700000001"), "   ", false, 25);

        assertWellFormed(xml);
        assertThat(xml).doesNotContain("<Say>");
    }

    @Test
    @DisplayName("recording is only asked for when it is switched on")
    void recordingIsOptional() {
        assertThat(VoiceXml.dial(List.of("+254700000001"), null, true, 25))
                .contains("record=\"true\"");
        assertThat(VoiceXml.dial(List.of("+254700000001"), null, false, 25))
                .doesNotContain("record");
    }

    @Test
    @DisplayName("an ampersand in the greeting does not break the document")
    void escapesAmpersand() {
        // The operator typed a perfectly reasonable sentence. Unescaped, this is
        // a document the provider rejects, and nothing would connect the silent
        // phone line back to the ampersand.
        String xml = VoiceXml.dial(List.of("+254700000001"), "Karibu & welcome to SPA WiFi",
                false, 25);

        assertWellFormed(xml);
        assertThat(xml).contains("Karibu &amp; welcome");
        assertThat(xml).doesNotContain("Karibu & welcome");
    }

    @Test
    @DisplayName("angle brackets and quotes in a greeting are escaped")
    void escapesEverythingElse() {
        String xml = VoiceXml.sayAndHangUp("We are <closed> for the \"holiday\" & back Monday");

        assertWellFormed(xml);
        assertThat(xml).contains("&lt;closed&gt;");
        assertThat(xml).contains("&quot;holiday&quot;");
        assertThat(xml).contains("&amp;");
    }

    @Test
    @DisplayName("the ampersand is escaped once, not twice")
    void ampersandIsNotDoubleEscaped() {
        // Escaping & after < and > would turn the &lt; those produced into
        // &amp;lt; and the caller would hear the markup read out.
        assertThat(VoiceXml.escape("a < b & c")).isEqualTo("a &lt; b &amp; c");
        assertThat(VoiceXml.escape("&")).isEqualTo("&amp;");
        assertThat(VoiceXml.escape("&amp;")).isEqualTo("&amp;amp;");
    }

    @Test
    @DisplayName("nobody available says something rather than hanging up silently")
    void sayAndHangUp() {
        String xml = VoiceXml.sayAndHangUp("Everyone is busy, we will call you back.");

        assertWellFormed(xml);
        assertThat(xml).contains("Everyone is busy");
        assertThat(xml).doesNotContain("<Dial");
    }

    @Test
    @DisplayName("a blank message still says something")
    void blankMessageHasAFallback() {
        String xml = VoiceXml.sayAndHangUp(null);

        assertWellFormed(xml);
        // Silence teaches a customer that the number does not work.
        assertThat(xml).contains("<Say>");
        assertThat(xml).contains("Sorry");
    }

    @Test
    @DisplayName("rejecting is a Reject and nothing else")
    void reject() {
        String xml = VoiceXml.reject();

        assertWellFormed(xml);
        assertThat(xml).contains("<Reject/>");
        assertThat(xml).doesNotContain("<Say>");
    }

    @Test
    @DisplayName("dialling nobody is refused rather than producing an empty Dial")
    void dialNobody() {
        // An empty phoneNumbers attribute is a document the provider accepts and
        // does nothing with, which looks from here like the call simply vanished.
        assertThatThrownBy(() -> VoiceXml.dial(List.of(), "hello", false, 25))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoiceXml.dial(null, "hello", false, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the call is given a ceiling well above the ring time")
    void maxDurationIsGenerous() {
        String xml = VoiceXml.dial(List.of("+254700000001"), null, false, 25);

        assertWellFormed(xml);
        // A cap of 25 seconds would cut a support call off mid-sentence. The
        // ring time and the talk time are different things.
        assertThat(xml).contains("maxDuration=\"100\"");
    }
}
