package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Chargily over a real socket.
 *
 * <p>Algeria's money is unusual in this package in a quiet way: the dinar's
 * subunit has not circulated in decades, so Chargily works in whole dinars.
 * Reaching for the hundred that Paymob and Paystack want would multiply every
 * Algerian price by a hundred.
 *
 * <p>The other thing worth pinning is that its webhook is signed properly —
 * HMAC-SHA256 over the raw body — which puts it with Stripe and Wave rather than
 * with the rails whose callback has to be re-queried.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargilyHttpTest {

    private static final String SECRET = "test_sk_9f3a1c7e5b2d48f0a6c8e1b3d5f70921";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private FakeGateway chargily;
    private ChargilyProvider provider;

    @BeforeEach
    void setUp() {
        chargily = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "chargily", chargily.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.CHARGILY)
                .active(true)
                .secretKey(SECRET)
                .build()));
        country("DZ", "DZD");

        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://isp.example.dz/api/payments/mpesa/callback", null));
        provider = new ChargilyProvider(gateways, portalSettings, JSON, endpoints, urls);
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    @AfterEach
    void tearDown() {
        chargily.close();
    }

    private void checkoutOpens() {
        chargily.on("POST /checkouts",
                "{\"id\":\"01hj7q\",\"status\":\"pending\","
                + "\"checkout_url\":\"https://pay.chargily.dz/test/checkouts/01hj7q/pay\"}");
    }

    private static PaymentProvider.ChargeRequest request(String amount) {
        return new PaymentProvider.ChargeRequest(
                "213551234567", null, new BigDecimal(amount), "DZD", "HS-91",
                "1 hour of WiFi");
    }

    // ---------------------------------------------------------------- the money

    @Test
    @DisplayName("Whole dinars, not centimes")
    void amountIsWholeDinars() {
        checkoutOpens();

        provider.charge(request("500"));

        // 500, not 50000. The centime has not circulated in decades and Chargily
        // works in dinars -- so the hundred that Paymob and Paystack want would
        // charge a hundred times the price here.
        assertThat(chargily.call("/checkouts").body()).containsPattern("\"amount\":500[^0-9]");
        assertThat(chargily.call("/checkouts").body()).contains("\"currency\":\"dzd\"");
    }

    @Test
    @DisplayName("Dinars are counted whole")
    void dinarsAreWhole() {
        assertThat(ChargilyProvider.dinars(new BigDecimal("500"))).isEqualTo(500L);
        assertThat(ChargilyProvider.dinars(new BigDecimal("75"))).isEqualTo(75L);
        // A fractional price rounds rather than being sent with a decimal point
        // Chargily would refuse.
        assertThat(ChargilyProvider.dinars(new BigDecimal("99.4"))).isEqualTo(99L);
        assertThat(ChargilyProvider.dinars(new BigDecimal("99.5"))).isEqualTo(100L);
    }

    // ---------------------------------------------------------------- the charge

    @Test
    @DisplayName("The key authorises, and our reference rides in the metadata")
    void chargeBody() {
        checkoutOpens();

        PaymentProvider.Charge charge = provider.charge(request("500"));

        assertThat(chargily.call("/checkouts").header("Authorization"))
                .isEqualTo("Bearer " + SECRET);
        // metadata is where Chargily keeps something of ours and hands it back on
        // the checkout and in the webhook.
        assertThat(chargily.call("/checkouts").body()).contains("\"reference\":\"HS-91\"");
        assertThat(charge.providerRef()).isEqualTo("01hj7q");
        assertThat(charge.checkoutUrl()).isEqualTo("https://pay.chargily.dz/test/checkouts/01hj7q/pay");
    }

    @Test
    @DisplayName("Both card schemes are left available")
    void bothCardSchemesAreOffered() {
        checkoutOpens();

        provider.charge(request("500"));

        // payment_method is deliberately absent so Chargily shows the customer
        // both. Naming one would shut out whichever card they hold, and EDAHABIA
        // and CIB are held by largely different people.
        assertThat(chargily.call("/checkouts").body()).doesNotContain("payment_method");
    }

    @Test
    @DisplayName("Chargily is told where to call back")
    void webhookUrlPointsAtUs() {
        checkoutOpens();

        provider.charge(request("500"));

        assertThat(chargily.call("/checkouts").body())
                .contains("https://isp.example.dz/api/payments/chargily/webhook");
    }

    @Test
    @DisplayName("A refusal is surfaced with Chargily's own message")
    void refusalSurfaces() {
        chargily.on("POST /checkouts", 422,
                "{\"message\":\"The given data was invalid.\","
                + "\"errors\":{\"amount\":[\"The amount must be at least 75.\"]}}");

        // The field error, not the generic wrapper: "must be at least 75" is the
        // sentence an operator can act on.
        assertThatThrownBy(() -> provider.charge(request("10")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 75");
    }

    @Test
    @DisplayName("An unauthenticated key is reported as such")
    void badKeySurfaces() {
        // Verbatim from the live API.
        chargily.on("POST /checkouts", 401, "{\"message\":\"Unauthenticated.\"}");

        assertThatThrownBy(() -> provider.charge(request("500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unauthenticated");
    }

    // --------------------------------------------------------------- the webhook

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String PAID_EVENT = """
            {"type":"checkout.paid","data":{"id":"01hj7q","status":"paid","amount":500,
             "currency":"dzd","invoice_id":"INV-77",
             "metadata":[{"reference":"HS-91"}]}}""";

    @Test
    @DisplayName("A genuine webhook settles")
    void genuineWebhookSettles() {
        Optional<PaymentProvider.Settlement> settled = provider.settle(
                PAID_EVENT.getBytes(StandardCharsets.UTF_8),
                Map.of("signature", sign(PAID_EVENT)));

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().reference()).isEqualTo("HS-91");
        assertThat(settled.get().receipt()).isEqualTo("INV-77");
        assertThat(settled.get().amount()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(settled.get().currency()).isEqualTo("DZD");
    }

    @Test
    @DisplayName("A forged webhook is refused")
    void forgedWebhookIsRefused() {
        // This endpoint mints vouchers.
        assertThatThrownBy(() -> provider.settle(
                PAID_EVENT.getBytes(StandardCharsets.UTF_8),
                Map.of("signature", "0".repeat(64))))
                .hasMessageContaining("signature did not match");
    }

    @Test
    @DisplayName("An unsigned webhook is refused")
    void unsignedWebhookIsRefused() {
        assertThatThrownBy(() -> provider.settle(
                PAID_EVENT.getBytes(StandardCharsets.UTF_8), Map.of()))
                .hasMessageContaining("no signature");
    }

    @Test
    @DisplayName("The signature is over the exact bytes")
    void signatureIsOverTheRawBody() {
        // A body signed correctly but delivered with one byte changed must fail.
        // Reserialising before verifying would let this through, and that is the
        // trap every raw-body rail here shares.
        String tampered = PAID_EVENT.replace("\"amount\":500", "\"amount\":5");

        assertThatThrownBy(() -> provider.settle(
                tampered.getBytes(StandardCharsets.UTF_8),
                Map.of("signature", sign(PAID_EVENT))))
                .hasMessageContaining("signature did not match");
    }

    @Test
    @DisplayName("A signed event that is not an outcome settles nothing")
    void pendingEventSettlesNothing() {
        String pending = PAID_EVENT.replace("\"status\":\"paid\"", "\"status\":\"pending\"");

        assertThat(provider.settle(pending.getBytes(StandardCharsets.UTF_8),
                Map.of("signature", sign(pending)))).isEmpty();
    }

    // --------------------------------------------------------------- the polling

    @Test
    @DisplayName("A paid checkout can be asked about")
    void pollReadsAPaidCheckout() {
        chargily.on("GET /checkouts/01hj7q",
                "{\"id\":\"01hj7q\",\"status\":\"paid\",\"amount\":500,"
                + "\"metadata\":[{\"reference\":\"HS-91\"}]}");

        Optional<PaymentProvider.Settlement> settled = provider.poll("01hj7q");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().reference()).isEqualTo("HS-91");
    }

    @Test
    @DisplayName("A checkout still pending is not called failed")
    void pendingIsNotAFailure() {
        chargily.on("GET /checkouts/01hj7q",
                "{\"id\":\"01hj7q\",\"status\":\"pending\"}");

        assertThat(provider.poll("01hj7q")).isEmpty();
    }

    @Test
    @DisplayName("A failed or expired checkout settles as unpaid")
    void failedSettles() {
        for (String status : new String[]{"failed", "canceled", "expired"}) {
            chargily.on("GET /checkouts/x-" + status,
                    "{\"id\":\"x\",\"status\":\"" + status + "\"}");

            Optional<PaymentProvider.Settlement> settled = provider.poll("x-" + status);
            assertThat(settled).as(status).isPresent();
            assertThat(settled.get().paid()).isFalse();
        }
    }

    // ------------------------------------------------------------------- market

    @Test
    @DisplayName("Outside Algeria it is not offered")
    void outsideAlgeriaItIsNotOffered() {
        country("KE", "KES");

        assertThat(provider.usable()).isFalse();
        assertThatThrownBy(() -> provider.charge(request("500")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(chargily.calls()).isEmpty();
    }

    @Test
    @DisplayName("Prices have to be in dinars")
    void currencyMustAgree() {
        country("DZ", "KES");

        assertThat(provider.usable()).isFalse();
        assertThat(chargily.calls()).isEmpty();
    }

    @Test
    @DisplayName("A test key is not mistaken for a live one")
    void testKeyIsRecognised() {
        // Chargily prefixes its keys, so unlike Vodacom or EMIS the key itself
        // says which mode it is -- and an operator cannot set a dropdown to the
        // opposite of reality.
        assertThat(PaymentGateway.builder().kind(PaymentGateway.Kind.CHARGILY)
                .secretKey("test_sk_abc").build().isLive()).isFalse();
        assertThat(PaymentGateway.builder().kind(PaymentGateway.Kind.CHARGILY)
                .secretKey("live_sk_abc").build().isLive()).isTrue();
    }
}
