package com.spalimited.hotspotbilling.service.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paynow's hash, its wire format, and its verdicts.
 *
 * <p>The hash is the whole of Paynow's security and the easiest thing here to
 * get quietly wrong: it is order-dependent, and the same values in a different
 * order produce a different digest, which shows up as every message being
 * rejected with nothing to say why. It is checked against a digest computed
 * independently in the test rather than against whatever the code produces.
 */
class PaynowTest {

    private static final String KEY = "3e9fed89-60e1-4e4a-bf0c-7a2b7bcfa8d1";

    private static final String[] STATUS_ORDER = {
            "reference", "paynowreference", "amount", "status", "pollurl"};

    private static Map<String, String> paidReply() {
        Map<String, String> reply = new LinkedHashMap<>();
        reply.put("reference", "HOTSPOT-42");
        reply.put("paynowreference", "12345678");
        reply.put("amount", "5.00");
        reply.put("status", "Paid");
        reply.put("pollurl", "https://www.paynow.co.zw/Interface/CheckPayment/?guid=abc");
        return reply;
    }

    /** The algorithm as Paynow documents it, written out longhand. */
    private static String expectedHash(Map<String, String> fields, String[] order) {
        StringBuilder joined = new StringBuilder();
        for (String key : order) {
            joined.append(fields.getOrDefault(key, ""));
        }
        joined.append(KEY);
        return PaynowProvider.sha512Hex(joined.toString()).toUpperCase(java.util.Locale.ROOT);
    }

    @Test
    @DisplayName("The hash is SHA-512 over the values in order, salted with the integration key")
    void hashMatchesTheDocumentedAlgorithm() {
        Map<String, String> reply = paidReply();
        assertThat(PaynowProvider.hash(reply, STATUS_ORDER, KEY))
                .isEqualTo(expectedHash(reply, STATUS_ORDER))
                .hasSize(128)
                .isUpperCase();
    }

    @Test
    @DisplayName("Order is part of the algorithm, not a detail")
    void orderChangesTheHash() {
        Map<String, String> reply = paidReply();
        String[] shuffled = {"amount", "reference", "paynowreference", "status", "pollurl"};

        // Same values, different order. A map iteration order that happened to
        // work locally and differed in production would fail every message.
        assertThat(PaynowProvider.hash(reply, shuffled, KEY))
                .isNotEqualTo(PaynowProvider.hash(reply, STATUS_ORDER, KEY));
    }

    @Test
    @DisplayName("A genuine reply passes its hash check")
    void genuineReplyAccepted() {
        Map<String, String> reply = paidReply();
        reply.put("hash", expectedHash(reply, STATUS_ORDER));

        assertThat(PaynowProvider.hashMatches(reply, STATUS_ORDER, KEY)).isTrue();
    }

    @Test
    @DisplayName("A tampered amount breaks the hash")
    void tamperedReplyRejected() {
        Map<String, String> reply = paidReply();
        reply.put("hash", expectedHash(reply, STATUS_ORDER));
        // Somebody in the middle turning a five-dollar payment into five hundred.
        reply.put("amount", "500.00");

        assertThat(PaynowProvider.hashMatches(reply, STATUS_ORDER, KEY)).isFalse();
    }

    @Test
    @DisplayName("A reply hashed with somebody else's key is refused")
    void wrongKeyRejected() {
        Map<String, String> reply = paidReply();
        StringBuilder joined = new StringBuilder();
        for (String key : STATUS_ORDER) {
            joined.append(reply.getOrDefault(key, ""));
        }
        joined.append("not-our-integration-key");
        reply.put("hash", PaynowProvider.sha512Hex(joined.toString()).toUpperCase(java.util.Locale.ROOT));

        assertThat(PaynowProvider.hashMatches(reply, STATUS_ORDER, KEY)).isFalse();
    }

    @Test
    @DisplayName("A reply with no hash at all is refused rather than trusted")
    void missingHashRejected() {
        assertThat(PaynowProvider.hashMatches(paidReply(), STATUS_ORDER, KEY)).isFalse();
    }

    @Test
    @DisplayName("Hash comparison ignores case, because Paynow is inconsistent about it")
    void hashCaseInsensitive() {
        Map<String, String> reply = paidReply();
        reply.put("hash", expectedHash(reply, STATUS_ORDER).toLowerCase(java.util.Locale.ROOT));

        assertThat(PaynowProvider.hashMatches(reply, STATUS_ORDER, KEY)).isTrue();
    }

    // --- Their wire format, which is not JSON ---

    @Test
    @DisplayName("A URL-encoded reply parses, with keys lowercased")
    void parsesUrlEncoded() {
        Map<String, String> parsed = PaynowProvider.parse(
                "Status=Paid&Reference=HOTSPOT-42&Amount=5.00"
                        + "&PollUrl=https%3A%2F%2Fwww.paynow.co.zw%2FInterface%2FCheckPayment%2F%3Fguid%3Dabc");

        assertThat(parsed).containsEntry("status", "Paid").containsEntry("reference", "HOTSPOT-42");
        // Decoded, not left percent-encoded — a poll URL that is never decoded
        // is a poll URL that is never reachable.
        assertThat(parsed.get("pollurl"))
                .isEqualTo("https://www.paynow.co.zw/Interface/CheckPayment/?guid=abc");
    }

    @Test
    @DisplayName("Rubbish parses to nothing rather than throwing mid-sweep")
    void parsesRubbishSafely() {
        assertThat(PaynowProvider.parse(null)).isEmpty();
        assertThat(PaynowProvider.parse("")).isEmpty();
        assertThat(PaynowProvider.parse("no-equals-sign")).isEmpty();
    }

    // --- Verdicts ---

    @Test
    @DisplayName("Paid, and the goods-workflow states that also mean paid")
    void paidStates() {
        for (String state : new String[]{"Paid", "Awaiting Delivery", "Delivered"}) {
            Map<String, String> reply = paidReply();
            reply.put("status", state);
            var settlement = PaynowProvider.verdict(reply, "poll-url");
            assertThat(settlement).as("%s", state).isPresent();
            assertThat(settlement.get().paid()).as("%s", state).isTrue();
            assertThat(settlement.get().receipt()).isEqualTo("12345678");
        }
    }

    @Test
    @DisplayName("Cancelled and failed are refusals")
    void failedStates() {
        for (String state : new String[]{"Cancelled", "Failed", "Disputed", "Refunded"}) {
            Map<String, String> reply = paidReply();
            reply.put("status", state);
            var settlement = PaynowProvider.verdict(reply, "poll-url");
            assertThat(settlement).as("%s", state).isPresent();
            assertThat(settlement.get().paid()).as("%s", state).isFalse();
        }
    }

    @Test
    @DisplayName("A customer still deciding is not a failure")
    void pendingStates() {
        // "Sent" means the PIN prompt is on their phone right now. Calling that
        // a failure cancels a sale mid-keystroke.
        for (String state : new String[]{"Created", "Sent", "Something New"}) {
            Map<String, String> reply = paidReply();
            reply.put("status", state);
            assertThat(PaynowProvider.verdict(reply, "poll-url")).as("%s", state).isEmpty();
        }
    }
}
