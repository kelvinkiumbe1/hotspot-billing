package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orange Money, whose status query needs three values the interface carries as
 * one string.
 */
class OrangeMoneyProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("A reference survives a round trip")
    void referenceRoundTrips() {
        String ref = OrangeMoneyProvider.encodeRef("tok-abc", "HS-4471", 2500);
        OrangeMoneyProvider.Ref back = OrangeMoneyProvider.decodeRef(ref);

        assertThat(back).isNotNull();
        assertThat(back.payToken()).isEqualTo("tok-abc");
        assertThat(back.orderId()).isEqualTo("HS-4471");
        assertThat(back.amount()).isEqualTo(2500);
    }

    @Test
    @DisplayName("A pay token containing the separator still decodes")
    void payTokenMayContainASeparator() {
        // Orange's pay token is opaque. The order id and amount are ours and
        // cannot contain a pipe, which is why they come first — splitting from
        // the other end would truncate a token that happened to hold one.
        String ref = OrangeMoneyProvider.encodeRef("a|b|c", "HS-1", 100);
        OrangeMoneyProvider.Ref back = OrangeMoneyProvider.decodeRef(ref);

        assertThat(back.payToken()).isEqualTo("a|b|c");
        assertThat(back.orderId()).isEqualTo("HS-1");
        assertThat(back.amount()).isEqualTo(100);
    }

    @Test
    @DisplayName("The reference starts with the prefix a notification can search on")
    void prefixMatchesTheStoredReference() {
        // This is the whole reason the order id goes first. Orange's
        // notification quotes it and nothing else usable, and the stored
        // reference has to be findable from it alone.
        String ref = OrangeMoneyProvider.encodeRef("tok", "HS-99", 500);

        assertThat(ref).startsWith(OrangeMoneyProvider.refPrefix("HS-99"));
    }

    @Test
    @DisplayName("Anything that is not one of ours decodes to nothing")
    void rubbishDecodesToNull() {
        assertThat(OrangeMoneyProvider.decodeRef(null)).isNull();
        assertThat(OrangeMoneyProvider.decodeRef("")).isNull();
        // A bare pay token, e.g. a row written by some other rail.
        assertThat(OrangeMoneyProvider.decodeRef("just-a-token")).isNull();
        assertThat(OrangeMoneyProvider.decodeRef("HS-1|only-two-parts")).isNull();
        // Amount is not a number.
        assertThat(OrangeMoneyProvider.decodeRef("HS-1|lots|tok")).isNull();
    }

    @Test
    @DisplayName("SUCCESS is paid, FAILED and EXPIRED are not, PENDING is neither")
    void statusesReadCorrectly() {
        assertThat(read("SUCCESS")).isPresent();
        assertThat(read("SUCCESS").get().paid()).isTrue();
        assertThat(read("FAILED").get().paid()).isFalse();
        assertThat(read("EXPIRED").get().paid()).isFalse();

        // INITIATED and PENDING mean the customer has not finished. Reporting
        // either as "not paid" cancels a live sale.
        assertThat(read("INITIATED")).isEmpty();
        assertThat(read("PENDING")).isEmpty();
        assertThat(read("SOMETHING_NEW")).isEmpty();
    }

    @Test
    @DisplayName("A successful payment reports no amount rather than a made-up one")
    void reportsNoAmount() {
        // Orange's status document does not contain the amount. Claiming zero is
        // what made every webhook-settled Airtel payment fail its amount check
        // and get marked FAILED with the customer already charged.
        assertThat(read("SUCCESS").get().amount()).isNull();
    }

    @Test
    @DisplayName("The order id is read out of a notification, quoted or bare")
    void findsTheOrderId() {
        assertThat(OrangeMoneyProvider.notifiedOrderId(
                bytes("{\"status\":\"SUCCESS\",\"order_id\":\"HS-77\",\"amount\":300}")))
                .isEqualTo("HS-77");
        // Some markets send it unquoted.
        assertThat(OrangeMoneyProvider.notifiedOrderId(
                bytes("{\"order_id\":8891,\"amount\":300}")))
                .isEqualTo("8891");
        assertThat(OrangeMoneyProvider.notifiedOrderId(bytes("{\"status\":\"SUCCESS\"}"))).isNull();
        assertThat(OrangeMoneyProvider.notifiedOrderId(new byte[0])).isNull();
    }

    @Test
    @DisplayName("The plain webhook path refuses rather than pretending to settle")
    void plainSettleIsRefused() {
        // Orange cannot be asked anything without the pay token, and the token
        // cannot go in the notif_url either — Orange only issues it in the reply
        // to the request that sets that URL. Acknowledging here would tell
        // Orange a payment was handled when nothing had happened.
        OrangeMoneyProvider provider = new OrangeMoneyProvider(null, null, null, null);
        assertThat(catchThrowable(() -> provider.settle(bytes("{}"), java.util.Map.of())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static Optional<PaymentProvider.Settlement> read(String status) {
        return OrangeMoneyProvider.read(
                MAPPER.readTree("{\"status\":\"" + status + "\",\"txnid\":\"OM-1\"}"),
                "HS-1|100|tok", "HS-1");
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Orange is an automatic rail and needs its merchant key to be ready")
    void configuredNeedsThreeFields() {
        PaymentGateway two = PaymentGateway.builder()
                .kind(PaymentGateway.Kind.ORANGE_MONEY)
                .consumerKey("id").consumerSecret("secret").build();
        // Two of three is the state an operator lands in by filling the fields
        // that look like every other rail's, and Orange refuses those payments
        // with an error that does not mention the merchant key.
        assertThat(two.isConfigured()).isFalse();

        PaymentGateway three = PaymentGateway.builder()
                .kind(PaymentGateway.Kind.ORANGE_MONEY)
                .consumerKey("id").consumerSecret("secret").shortCode("MK-1").build();
        assertThat(three.isConfigured()).isTrue();
        assertThat(three.isAutomatic()).isTrue();
    }
}
