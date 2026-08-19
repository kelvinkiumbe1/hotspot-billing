package com.spalimited.hotspotbilling.service.payments;

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
 * Paymob over a real socket.
 *
 * <p>Two things here are unlike anything else in this package. A charge is three
 * chained calls rather than one, so there are three places to send the wrong
 * field. And the webhook signature is computed over twenty named values in a
 * fixed order rather than over the body — which means the obvious implementation,
 * the one every other rail here uses, fails every single time and looks like an
 * attack while doing it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymobHttpTest {

    private static final String API_KEY = "ZXlKaGJHY2lPaUpJVXpVeE1pSjkudGVzdC1rZXk";
    private static final String HMAC_SECRET = "9A1B2C3D4E5F60718293A4B5C6D7E8F9";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private FakeGateway paymob;
    private PaymobProvider provider;

    @BeforeEach
    void setUp() {
        paymob = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "paymob", paymob.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.PAYMOB)
                .active(true)
                .environment(PaymentGateway.Environment.PRODUCTION)
                .secretKey(API_KEY)
                .webhookSecret(HMAC_SECRET)
                .shortCode("4077777")          // integration id
                .publicKey("890123")           // iframe id
                .build()));
        country("EG", "EGP");
        provider = new PaymobProvider(gateways, portalSettings, JSON, endpoints);
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    @AfterEach
    void tearDown() {
        paymob.close();
    }

    private void authAndOrderSucceed() {
        paymob.on("POST /auth/tokens", "{\"token\":\"auth-tok-1\"}");
        paymob.on("POST /ecommerce/orders", "{\"id\":778899}");
        paymob.on("POST /acceptance/payment_keys", "{\"token\":\"pay-key-xyz\"}");
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "201012345678", null, new BigDecimal("150"), "EGP", "HS-77",
                "1 hour of WiFi");
    }

    // ------------------------------------------------------------------ charge

    @Test
    @DisplayName("Three calls in order, each carrying the last one's answer")
    void theChainOfThreeCalls() {
        authAndOrderSucceed();

        PaymentProvider.Charge charge = provider.charge(request());

        // The API key buys a token and is used nowhere else.
        assertThat(paymob.call("/auth/tokens").body()).contains(API_KEY);
        // The token then goes in the body of both later calls -- not a header,
        // which is where anybody who has used another gateway will put it.
        assertThat(paymob.call("/ecommerce/orders").body()).contains("\"auth_token\":\"auth-tok-1\"");
        String key = paymob.call("/acceptance/payment_keys").body();
        assertThat(key).contains("\"auth_token\":\"auth-tok-1\"");
        // The order id from call two is what call three is about.
        assertThat(key).contains("\"order_id\":\"778899\"");
        assertThat(key).contains("\"integration_id\":\"4077777\"");

        // And the page is Paymob's own iframe, carrying the payment key.
        assertThat(charge.checkoutUrl())
                .isEqualTo(paymob.url() + "/acceptance/iframes/890123?payment_token=pay-key-xyz");
        // The order id, because that is what the webhook's order.id carries.
        assertThat(charge.providerRef()).isEqualTo("778899");
    }

    @Test
    @DisplayName("Pounds go as piastres, which is the opposite of the wallets next door")
    void amountIsMinorUnits() {
        authAndOrderSucceed();

        provider.charge(request());

        // 150 pounds is 15000 piastres. Sending 150 undercharges by a hundred.
        assertThat(paymob.call("/ecommerce/orders").body())
                .containsPattern("\"amount_cents\":15000[^0-9]");
        assertThat(paymob.call("/acceptance/payment_keys").body())
                .containsPattern("\"amount_cents\":15000[^0-9]");
    }

    @Test
    @DisplayName("Piastres are counted, not rounded away")
    void amountKeepsItsPiastres() {
        assertThat(PaymobProvider.piastres(new BigDecimal("150"))).isEqualTo(15000L);
        assertThat(PaymobProvider.piastres(new BigDecimal("150.75"))).isEqualTo(15075L);
        assertThat(PaymobProvider.piastres(new BigDecimal("0.05"))).isEqualTo(5L);
    }

    @Test
    @DisplayName("Our reference is what Paymob is told to remember")
    void ourReferenceGoesOnTheOrder() {
        authAndOrderSucceed();

        provider.charge(request());

        // merchant_order_id is how a settlement finds its way back to a payment.
        assertThat(paymob.call("/ecommerce/orders").body())
                .contains("\"merchant_order_id\":\"HS-77\"");
    }

    @Test
    @DisplayName("Every billing field is filled, because Paymob refuses a gap")
    void billingDataIsComplete() {
        authAndOrderSucceed();

        provider.charge(request());

        // Paymob rejects the payment key outright if any of these is absent, and
        // a hotspot customer has given us a phone number and nothing else.
        String body = paymob.call("/acceptance/payment_keys").body();
        for (String field : new String[]{"first_name", "last_name", "email", "phone_number",
                "apartment", "floor", "street", "building", "shipping_method",
                "postal_code", "city", "state", "country"}) {
            assertThat(body).as("billing_data.%s", field).contains("\"" + field + "\"");
        }
        assertThat(body).contains("\"phone_number\":\"201012345678\"");
        assertThat(body).contains("@");
    }

    @Test
    @DisplayName("A refused API key is reported with Paymob's own words")
    void badApiKeySurfaces() {
        paymob.on("POST /auth/tokens", 401,
                "{\"detail\":\"Incorrect authentication credentials.\"}");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incorrect authentication credentials");
        assertThat(paymob.calls()).noneMatch(c -> c.path().contains("orders"));
    }

    @Test
    @DisplayName("A refused payment key stops before a customer is sent anywhere")
    void badIntegrationIdSurfaces() {
        paymob.on("POST /auth/tokens", "{\"token\":\"auth-tok-1\"}");
        paymob.on("POST /ecommerce/orders", "{\"id\":778899}");
        paymob.on("POST /acceptance/payment_keys", 400,
                "{\"message\":\"Invalid integration id\"}");

        // Better than a page that loads and cannot be paid on.
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid integration id");
    }

    @Test
    @DisplayName("One token is fetched, not one per call")
    void theTokenIsReused() {
        authAndOrderSucceed();

        provider.charge(request());
        provider.charge(request());

        assertThat(paymob.calls().stream().filter(c -> c.path().equals("/auth/tokens")).count())
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------- webhook

    /** A transaction callback in Paymob's shape, with the fields it signs. */
    private static String transactionBody(boolean success, String extra) {
        return """
                {"type":"TRANSACTION","obj":{
                  "id":123456789,
                  "amount_cents":15000,
                  "created_at":"2026-08-19T10:00:00.000000",
                  "currency":"EGP",
                  "error_occured":false,
                  "has_parent_transaction":false,
                  "integration_id":4077777,
                  "is_3d_secure":true,
                  "is_auth":false,
                  "is_capture":false,
                  "is_refunded":false,
                  "is_standalone_payment":true,
                  "is_voided":false,
                  "owner":55555,
                  "pending":false,
                  "success":%s,
                  "order":{"id":778899,"merchant_order_id":"HS-77"},
                  "source_data":{"pan":"2346","sub_type":"MasterCard","type":"card"}
                  %s}}""".formatted(success, extra);
    }

    /**
     * The signature Paymob would send, computed independently of the provider.
     *
     * <p>Written out longhand rather than by calling the provider's own method,
     * because a test that signs with the code under test proves the two agree and
     * nothing about whether either is right.
     */
    private static String sign(boolean success) {
        String joined = "15000"                      // amount_cents
                + "2026-08-19T10:00:00.000000"       // created_at
                + "EGP"                              // currency
                + "false"                            // error_occured (Paymob's spelling)
                + "false"                            // has_parent_transaction
                + "123456789"                        // id
                + "4077777"                          // integration_id
                + "true"                             // is_3d_secure
                + "false"                            // is_auth
                + "false"                            // is_capture
                + "false"                            // is_refunded
                + "true"                             // is_standalone_payment
                + "false"                            // is_voided
                + "778899"                           // order.id
                + "55555"                            // owner
                + "false"                            // pending
                + "2346"                             // source_data.pan
                + "MasterCard"                       // source_data.sub_type
                + "card"                             // source_data.type
                + success;                           // success
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<PaymentProvider.Settlement> settle(String body) {
        return provider.settle(body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    @Test
    @DisplayName("A genuine callback settles, signed over the fields and not the body")
    void genuineCallbackSettles() {
        String body = transactionBody(true, ",\"hmac\":\"" + sign(true) + "\"");

        Optional<PaymentProvider.Settlement> settled = settle(body);

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().reference()).isEqualTo("HS-77");
        assertThat(settled.get().providerRef()).isEqualTo("778899");
        // Read back as pounds, not piastres, or the receipt says 15000.
        assertThat(settled.get().amount()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(settled.get().currency()).isEqualTo("EGP");
    }

    @Test
    @DisplayName("The body itself is not what is signed")
    void signingTheRawBodyWouldNotWork() {
        // The obvious implementation, and the one every other rail here uses.
        // Proving it does not match is the point: without this, somebody
        // simplifying the provider to hmacHex(body) would find every test still
        // passing except the ones that matter.
        String body = transactionBody(true, "");
        String overTheBody = Signatures.hmacHex("HmacSHA512", HMAC_SECRET,
                body.getBytes(StandardCharsets.UTF_8));

        assertThat(overTheBody).isNotEqualTo(sign(true));
    }

    @Test
    @DisplayName("A forged callback is refused")
    void forgedCallbackIsRefused() {
        // This endpoint mints vouchers. Anyone who learns the URL would otherwise
        // have a free-internet generator.
        String body = transactionBody(true, ",\"hmac\":\"" + "0".repeat(128) + "\"");

        assertThatThrownBy(() -> settle(body)).hasMessageContaining("signature did not match");
    }

    @Test
    @DisplayName("A callback with no signature at all is refused")
    void unsignedCallbackIsRefused() {
        assertThatThrownBy(() -> settle(transactionBody(true, "")))
                .hasMessageContaining("no signature");
    }

    @Test
    @DisplayName("A declined payment settles as failed, not as nothing")
    void declinedSettlesAsFailed() {
        String body = transactionBody(false, ",\"hmac\":\"" + sign(false) + "\"");

        Optional<PaymentProvider.Settlement> settled = settle(body);

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isFalse();
    }

    @Test
    @DisplayName("A refund is not a purchase")
    void refundDoesNotIssueAVoucher() {
        // is_refunded arrives with success still true. Reading success alone gives
        // a voucher away for money on its way back out.
        String obj = transactionBody(true, ",\"hmac\":\"x\"")
                .replace("\"is_refunded\":false", "\"is_refunded\":true");
        // Signed correctly for the altered payload, so only the flag decides.
        String signed = obj.replace("\"hmac\":\"x\"", "\"hmac\":\""
                + PaymobProvider.signature(
                        JSON.readTree(obj.getBytes(StandardCharsets.UTF_8)).path("obj"),
                        HMAC_SECRET) + "\"");

        assertThat(settle(signed)).isEmpty();
    }

    @Test
    @DisplayName("A payment still pending is not called failed")
    void pendingIsNotAFailure() {
        String obj = transactionBody(false, ",\"hmac\":\"x\"")
                .replace("\"pending\":false", "\"pending\":true");
        String signed = obj.replace("\"hmac\":\"x\"", "\"hmac\":\""
                + PaymobProvider.signature(
                        JSON.readTree(obj.getBytes(StandardCharsets.UTF_8)).path("obj"),
                        HMAC_SECRET) + "\"");

        // A customer mid-3DS. Failing them here cancels a sale in progress.
        assertThat(settle(signed)).isEmpty();
    }

    // ------------------------------------------------------------------ market

    @Test
    @DisplayName("Outside Egypt it is not offered")
    void outsideEgyptItIsNotOffered() {
        country("KE", "KES");

        assertThat(provider.usable()).isFalse();
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(paymob.calls()).isEmpty();
    }

    @Test
    @DisplayName("Prices in the wrong currency stop it rather than mis-charging")
    void currencyMustAgree() {
        // Egypt set, but prices still in shillings. A plan priced 1000 would go
        // to Paymob as 1000 EGP, which is a different amount of money.
        country("EG", "KES");

        assertThat(provider.usable()).isFalse();
        assertThat(paymob.calls()).isEmpty();
    }

    @Test
    @DisplayName("Nothing about this rail claims to be pollable")
    void notPollable() {
        // Its webhook settles it, like the other hosted checkouts. A status
        // reader written against documentation and never run is how a good
        // payment gets marked failed.
        assertThat(provider.pollable()).isFalse();
        assertThat(provider.poll("778899")).isEmpty();
    }
}
