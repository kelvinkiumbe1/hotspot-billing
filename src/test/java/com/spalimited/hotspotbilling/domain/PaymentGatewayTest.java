package com.spalimited.hotspotbilling.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two questions the admin screen asks of a gateway: can it collect, and
 * is the money real. Both are worth pinning down, because getting either
 * wrong shows an operator a healthy-looking setup that collects nothing.
 */
class PaymentGatewayTest {

    private static PaymentGateway card(PaymentGateway.Kind kind, String secret, String webhook) {
        return PaymentGateway.builder().kind(kind).secretKey(secret).webhookSecret(webhook).build();
    }

    @Test
    @DisplayName("Stripe and Paystack test keys are not live money")
    void testKeysAreNotLive() {
        assertThat(card(PaymentGateway.Kind.STRIPE, "sk_test_abc", "whsec_x").isLive()).isFalse();
        assertThat(card(PaymentGateway.Kind.PAYSTACK, "sk_test_abc", null).isLive()).isFalse();
    }

    @Test
    @DisplayName("Flutterwave marks its test keys in the middle of the string")
    void flutterwaveTestKey() {
        assertThat(card(PaymentGateway.Kind.FLUTTERWAVE, "FLWSECK_TEST-abc", "hash").isLive()).isFalse();
        assertThat(card(PaymentGateway.Kind.FLUTTERWAVE, "FLWSECK-abc", "hash").isLive()).isTrue();
    }

    @Test
    @DisplayName("A live key is live regardless of the stored environment")
    void liveKeyWins() {
        PaymentGateway g = card(PaymentGateway.Kind.STRIPE, "sk_live_abc", "whsec_x");
        g.setEnvironment(PaymentGateway.Environment.SANDBOX);
        assertThat(g.isLive()).isTrue();
    }

    @Test
    @DisplayName("M-Pesa still decides live by environment, not by key")
    void mpesaUnchanged() {
        PaymentGateway g = PaymentGateway.builder()
                .kind(PaymentGateway.Kind.MPESA_API)
                .environment(PaymentGateway.Environment.SANDBOX)
                .build();
        assertThat(g.isLive()).isFalse();
        g.setEnvironment(PaymentGateway.Environment.PRODUCTION);
        assertThat(g.isLive()).isTrue();
    }

    @Test
    @DisplayName("A card gateway without its webhook secret cannot be trusted, so is not configured")
    void webhookSecretRequired() {
        assertThat(card(PaymentGateway.Kind.STRIPE, "sk_live_abc", null).isConfigured()).isFalse();
        assertThat(card(PaymentGateway.Kind.FLUTTERWAVE, "FLWSECK-abc", "  ").isConfigured()).isFalse();
        assertThat(card(PaymentGateway.Kind.STRIPE, "sk_live_abc", "whsec_x").isConfigured()).isTrue();
    }

    @Test
    @DisplayName("Paystack needs only the secret key, because that is what signs its webhooks")
    void paystackNeedsOnlyTheKey() {
        assertThat(card(PaymentGateway.Kind.PAYSTACK, "sk_live_abc", null).isConfigured()).isTrue();
        assertThat(card(PaymentGateway.Kind.PAYSTACK, null, null).isConfigured()).isFalse();
    }

    @Test
    @DisplayName("The card rails count as automatic; the hand-reconciled ones do not")
    void automatic() {
        for (PaymentGateway.Kind kind : new PaymentGateway.Kind[]{
                PaymentGateway.Kind.MPESA_API, PaymentGateway.Kind.PAYSTACK,
                PaymentGateway.Kind.FLUTTERWAVE, PaymentGateway.Kind.STRIPE}) {
            assertThat(PaymentGateway.builder().kind(kind).build().isAutomatic()).as("%s", kind).isTrue();
        }
        for (PaymentGateway.Kind kind : new PaymentGateway.Kind[]{
                PaymentGateway.Kind.MPESA_PAYBILL_MANUAL, PaymentGateway.Kind.MPESA_TILL_MANUAL,
                PaymentGateway.Kind.BANK_TRANSFER}) {
            assertThat(PaymentGateway.builder().kind(kind).build().isAutomatic()).as("%s", kind).isFalse();
        }
    }
}
