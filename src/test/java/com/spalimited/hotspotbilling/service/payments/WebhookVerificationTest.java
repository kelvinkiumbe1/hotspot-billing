package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The doors on the card webhooks.
 *
 * <p>These endpoints mint vouchers. An unverified one is a free-internet
 * generator for anyone who learns the URL, so every one of these checks is load
 * bearing — and unlike the API calls themselves, all of it can be proved
 * without a single live credential.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookVerificationTest {

    private static final String SECRET = "sk_test_3f8a1c9e5b2d47f0a6c8e1b3d5f70921";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private PaymentGatewayService gateways;

    private PaystackProvider paystack;
    private FlutterwaveProvider flutterwave;
    private StripeProvider stripe;

    @BeforeEach
    void setUp() {
        paystack = new PaystackProvider(gateways, JSON);
        flutterwave = new FlutterwaveProvider(gateways, JSON);
        stripe = new StripeProvider(gateways, JSON);
    }

    private void configure(PaymentGateway.Kind kind, String secret, String webhookSecret) {
        when(gateways.find(kind)).thenReturn(Optional.of(PaymentGateway.builder()
                .id(1L).kind(kind).active(true)
                .secretKey(secret).webhookSecret(webhookSecret).build()));
    }

    private static String hmac(String algorithm, String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Paystack: HMAC-SHA512 of the body, signed with the secret key ---

    private static final byte[] PAYSTACK_PAID = """
            {"event":"charge.success","data":{"reference":"HOTSPOT-42","amount":5000,
             "currency":"NGN","gateway_response":"Successful"}}""".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("Paystack: a genuine delivery settles, and the amount comes back in major units")
    void paystackAcceptsGenuine() {
        configure(PaymentGateway.Kind.PAYSTACK, SECRET, null);

        Optional<PaymentProvider.Settlement> s = paystack.settle(PAYSTACK_PAID,
                Map.of("x-paystack-signature", hmac("HmacSHA512", SECRET, PAYSTACK_PAID)));

        assertThat(s).isPresent();
        assertThat(s.get().paid()).isTrue();
        assertThat(s.get().reference()).isEqualTo("HOTSPOT-42");
        // 5000 minor units of naira is fifty naira, not five thousand.
        assertThat(s.get().amount()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("Paystack: an unsigned or wrongly signed body is refused")
    void paystackRefusesForgery() {
        configure(PaymentGateway.Kind.PAYSTACK, SECRET, null);

        assertThatThrownBy(() -> paystack.settle(PAYSTACK_PAID, Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> paystack.settle(PAYSTACK_PAID,
                Map.of("x-paystack-signature", hmac("HmacSHA512", "the-wrong-secret", PAYSTACK_PAID))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Paystack: a body altered after signing is refused")
    void paystackRefusesTampering() {
        configure(PaymentGateway.Kind.PAYSTACK, SECRET, null);
        String honest = hmac("HmacSHA512", SECRET, PAYSTACK_PAID);
        byte[] tampered = new String(PAYSTACK_PAID, StandardCharsets.UTF_8)
                .replace("5000", "500000").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> paystack.settle(tampered, Map.of("x-paystack-signature", honest)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Paystack: an event we do not act on is accepted and ignored, not failed")
    void paystackIgnoresOtherEvents() {
        configure(PaymentGateway.Kind.PAYSTACK, SECRET, null);
        byte[] refund = "{\"event\":\"refund.processed\",\"data\":{\"reference\":\"HOTSPOT-42\"}}"
                .getBytes(StandardCharsets.UTF_8);

        // Authentic but not a purchase outcome. Treating it as a failure would
        // mark a paid customer's payment failed.
        assertThat(paystack.settle(refund,
                Map.of("x-paystack-signature", hmac("HmacSHA512", SECRET, refund)))).isEmpty();
    }

    // --- Flutterwave: a shared hash in a header ---

    private static final byte[] FLW_PAID = """
            {"event":"charge.completed","data":{"id":99,"tx_ref":"HOTSPOT-42",
             "status":"successful","amount":50,"currency":"NGN","flw_ref":"FLW-1"}}"""
            .getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("Flutterwave: the right hash settles, in major units as it sends them")
    void flutterwaveAcceptsGenuine() {
        configure(PaymentGateway.Kind.FLUTTERWAVE, SECRET, "my-verif-hash");

        Optional<PaymentProvider.Settlement> s =
                flutterwave.settle(FLW_PAID, Map.of("verif-hash", "my-verif-hash"));

        assertThat(s).isPresent();
        assertThat(s.get().paid()).isTrue();
        // Flutterwave quotes the major unit, unlike Paystack. Fifty is fifty.
        assertThat(s.get().amount()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("Flutterwave: a wrong or missing hash is refused")
    void flutterwaveRefusesForgery() {
        configure(PaymentGateway.Kind.FLUTTERWAVE, SECRET, "my-verif-hash");

        assertThatThrownBy(() -> flutterwave.settle(FLW_PAID, Map.of("verif-hash", "guessed")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> flutterwave.settle(FLW_PAID, Map.of()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Flutterwave: with no hash configured the endpoint refuses rather than trusting")
    void flutterwaveRefusesWhenUnconfigured() {
        configure(PaymentGateway.Kind.FLUTTERWAVE, SECRET, null);

        // The tempting alternative — accept because the operator skipped a
        // field — leaves a voucher-minting endpoint open to the internet.
        assertThatThrownBy(() -> flutterwave.settle(FLW_PAID, Map.of("verif-hash", "anything")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no verification hash");
    }

    // --- Stripe: timestamped HMAC over "t.body", with replay protection ---

    private static final byte[] STRIPE_PAID = """
            {"type":"checkout.session.completed","data":{"object":{"id":"cs_1",
             "client_reference_id":"HOTSPOT-42","amount_total":5000,"currency":"usd",
             "payment_intent":"pi_1"}}}""".getBytes(StandardCharsets.UTF_8);

    private String stripeHeader(long epochSeconds, String secret, byte[] body) {
        byte[] signed = (epochSeconds + "." + new String(body, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        return "t=" + epochSeconds + ",v1=" + hmac("HmacSHA256", secret, signed);
    }

    @Test
    @DisplayName("Stripe: a fresh, correctly signed delivery settles in major units")
    void stripeAcceptsGenuine() {
        configure(PaymentGateway.Kind.STRIPE, SECRET, "whsec_test");
        long now = Instant.now().getEpochSecond();

        Optional<PaymentProvider.Settlement> s = stripe.settle(STRIPE_PAID,
                Map.of("Stripe-Signature", stripeHeader(now, "whsec_test", STRIPE_PAID)));

        assertThat(s).isPresent();
        assertThat(s.get().paid()).isTrue();
        assertThat(s.get().reference()).isEqualTo("HOTSPOT-42");
        assertThat(s.get().amount()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Stripe: the signature covers the timestamp too, not just the body")
    void stripeSignsTimestampAndBody() {
        configure(PaymentGateway.Kind.STRIPE, SECRET, "whsec_test");
        long now = Instant.now().getEpochSecond();
        // A signature over the body alone — the mistake almost everybody makes
        // implementing this — must not pass.
        String bodyOnly = "t=" + now + ",v1=" + hmac("HmacSHA256", "whsec_test", STRIPE_PAID);

        assertThatThrownBy(() -> stripe.settle(STRIPE_PAID, Map.of("Stripe-Signature", bodyOnly)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Stripe: a captured delivery cannot be replayed later")
    void stripeRefusesAReplay() {
        configure(PaymentGateway.Kind.STRIPE, SECRET, "whsec_test");
        long anHourAgo = Instant.now().minusSeconds(3600).getEpochSecond();

        // Perfectly signed, genuinely from Stripe, and hours old. Without the
        // time window this could be posted back for free internet forever.
        assertThatThrownBy(() -> stripe.settle(STRIPE_PAID,
                Map.of("Stripe-Signature", stripeHeader(anHourAgo, "whsec_test", STRIPE_PAID))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("time window");
    }

    @Test
    @DisplayName("Stripe: during a secret rotation either signature is accepted")
    void stripeAcceptsRotatedSecrets() {
        configure(PaymentGateway.Kind.STRIPE, SECRET, "whsec_new");
        long now = Instant.now().getEpochSecond();
        byte[] signed = (now + "." + new String(STRIPE_PAID, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        String twoSignatures = "t=" + now
                + ",v1=" + hmac("HmacSHA256", "whsec_old", signed)
                + ",v1=" + hmac("HmacSHA256", "whsec_new", signed);

        assertThatCode(() -> stripe.settle(STRIPE_PAID,
                Map.of("Stripe-Signature", twoSignatures))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Stripe: a malformed header is refused rather than half-read")
    void stripeRefusesMalformedHeader() {
        configure(PaymentGateway.Kind.STRIPE, SECRET, "whsec_test");

        assertThatThrownBy(() -> stripe.settle(STRIPE_PAID, Map.of("Stripe-Signature", "garbage")))
                .isInstanceOf(ResponseStatusException.class);
    }

    // --- The minor-unit arithmetic, which decides what a customer is charged ---

    @Test
    @DisplayName("Amounts convert to each provider's unit, which is not the same unit")
    void convertsAmountsCorrectly() {
        // Paystack and Stripe take hundredths for ordinary currencies.
        assertThat(PaystackProvider.minorUnits(new BigDecimal("50"), "NGN")).isEqualTo(5000);
        assertThat(StripeProvider.minorUnits(new BigDecimal("12.50"), "USD")).isEqualTo(1250);
        // But some currencies have no subunit, and multiplying those by a
        // hundred charges a customer a hundred times the price.
        assertThat(StripeProvider.minorUnits(new BigDecimal("500"), "UGX")).isEqualTo(500);
        assertThat(PaystackProvider.minorUnits(new BigDecimal("500"), "JPY")).isEqualTo(500);
        assertThat(StripeProvider.majorUnits(1250, "USD")).isEqualByComparingTo("12.50");
        assertThat(StripeProvider.majorUnits(500, "UGX")).isEqualByComparingTo("500");
    }
}
