package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PaymentMandate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Push against pull — the distinction the whole of recurring rests on.
 *
 * <p>M-Pesa Ratiba sends money on its own; a stored authorisation only moves
 * when this system charges it. Confusing the two is expensive in both
 * directions: charging a Ratiba mandate takes the money twice, and waiting on a
 * token mandate collects nothing while the customer is no longer being chased.
 */
class MandateModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PaymentMandate ratiba() {
        return PaymentMandate.builder()
                .subscriberId(1L).provider(PaymentGateway.Kind.MPESA_API.name())
                .model(PaymentMandate.Model.PUSH)
                .status(PaymentMandate.Status.ACTIVE)
                .startsOn(LocalDate.now()).build();
    }

    private static PaymentMandate token(String value, PaymentMandate.Status status) {
        return PaymentMandate.builder()
                .subscriberId(2L).provider(PaymentGateway.Kind.PAYSTACK.name())
                .model(PaymentMandate.Model.PULL)
                .status(status).token(value)
                .startsOn(LocalDate.now()).build();
    }

    @Test
    @DisplayName("A Ratiba mandate stops the chasing but must never be charged by us")
    void ratibaIsNotOursToCharge() {
        PaymentMandate m = ratiba();

        assertThat(m.isCollecting()).as("nobody should be chased").isTrue();
        assertThat(m.weCollect())
                .as("Safaricom is already sending it; charging as well takes it twice")
                .isFalse();
    }

    @Test
    @DisplayName("An authorised token mandate is ours to charge")
    void tokenIsOursToCharge() {
        PaymentMandate m = token("AUTH_abc", PaymentMandate.Status.ACTIVE);

        assertThat(m.isCollecting()).isTrue();
        assertThat(m.weCollect()).isTrue();
    }

    @Test
    @DisplayName("A pull mandate with no token yet is neither collected nor trusted")
    void awaitingConsentCollectsNothing() {
        PaymentMandate m = token(null, PaymentMandate.Status.PENDING);

        // The customer has been asked and has not paid. Chasing must continue,
        // or they lapse in silence while the admin shows a standing order.
        assertThat(m.isCollecting()).isFalse();
        assertThat(m.weCollect()).isFalse();
    }

    @Test
    @DisplayName("An ACTIVE pull mandate with no token is flagged as suspect")
    void activeWithoutATokenIsSuspect() {
        // The dangerous shape: it reads as working, the operator has stopped
        // chasing, and there is nothing to charge.
        assertThat(token(null, PaymentMandate.Status.ACTIVE).isSuspect()).isTrue();
        assertThat(token("AUTH_abc", PaymentMandate.Status.ACTIVE).isSuspect()).isFalse();
    }

    // --- what each rail hands back ---

    @Test
    @DisplayName("Paystack: only a reusable authorization is stored")
    void paystackReusableOnly() {
        PaystackProvider p = new PaystackProvider(null, MAPPER, null);

        assertThat(p.reusableToken(body("""
                {"data":{"authorization":{"authorization_code":"AUTH_ok","reusable":true}}}""")))
                .contains("AUTH_ok");

        // A one-time bank transfer or USSD payment produces an authorization
        // that looks identical and cannot be charged again. Storing it gives the
        // operator a mandate that fails on its first renewal, by which point
        // they have stopped chasing the customer.
        assertThat(p.reusableToken(body("""
                {"data":{"authorization":{"authorization_code":"AUTH_no","reusable":false}}}""")))
                .isEmpty();
        assertThat(p.reusableToken(body("{\"data\":{}}"))).isEmpty();
        assertThat(p.reusableToken(body("not json"))).isEmpty();
    }

    @Test
    @DisplayName("Flutterwave: a token only from a successful card charge")
    void flutterwaveCardOnly() {
        FlutterwaveProvider f = new FlutterwaveProvider(null, MAPPER);

        assertThat(f.reusableToken(body("""
                {"data":{"status":"successful","card":{"token":"flw-tok"}}}""")))
                .contains("flw-tok");
        // Mobile money through Flutterwave leaves no token at all.
        assertThat(f.reusableToken(body("""
                {"data":{"status":"successful"}}"""))).isEmpty();
        // And a failed charge authorises nothing.
        assertThat(f.reusableToken(body("""
                {"data":{"status":"failed","card":{"token":"flw-tok"}}}"""))).isEmpty();
    }

    @Test
    @DisplayName("Stripe: both halves or nothing")
    void stripeNeedsCustomerAndMethod() {
        StripeProvider s = new StripeProvider(null, MAPPER);

        assertThat(s.reusableToken(body("""
                {"data":{"object":{"customer":"cus_1","payment_method":"pm_1"}}}""")))
                .contains("cus_1" + StripeProvider.TOKEN_SEPARATOR + "pm_1");
        // A payment method with no customer cannot be charged again, and half a
        // pair stored is a mandate that fails at renewal.
        assertThat(s.reusableToken(body("""
                {"data":{"object":{"payment_method":"pm_1"}}}"""))).isEmpty();
        assertThat(s.reusableToken(body("""
                {"data":{"object":{"customer":"cus_1"}}}"""))).isEmpty();
    }

    @Test
    @DisplayName("The rails that cannot charge again say so")
    void honestAboutWhatCannotRecur() {
        // Claiming the ability and failing at renewal is worse than not claiming
        // it: the operator stops chasing on the strength of it.
        assertThat(new AirtelProvider(null, null).supportsRecurring()).isFalse();
        assertThat(new WaveProvider(null, null, null, MAPPER).supportsRecurring()).isFalse();
        assertThat(new OrangeMoneyProvider(null, null, null).supportsRecurring()).isFalse();
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
