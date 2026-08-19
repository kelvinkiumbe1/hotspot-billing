package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
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

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Stripe over a real socket.
 *
 * <p>Stripe is the only rail here that takes form-encoded bodies rather than
 * JSON, and its nested bracket keys ({@code line_items[0][price_data][currency]})
 * are the sort of thing that is either exactly right or silently ignored. It is
 * also one of the three that can charge a saved card with nobody present.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StripeHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    private FakeGateway stripe;
    private StripeProvider provider;

    @BeforeEach
    void setUp() {
        stripe = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "stripe", stripe.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.STRIPE)
                .active(true)
                .secretKey("sk_test_stripe")
                .webhookSecret("whsec_x")
                .build()));

        provider = new StripeProvider(gateways, new ObjectMapper(), endpoints);
    }

    @AfterEach
    void tearDown() {
        stripe.close();
    }

    private static PaymentProvider.ChargeRequest request(String currency, String amount) {
        return new PaymentProvider.ChargeRequest(
                "254712345678", null, new BigDecimal(amount), currency, "HS-5", "1 hour of WiFi");
    }

    private String decodedBody(String path) {
        return URLDecoder.decode(stripe.call(path).body(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("A checkout session is form-encoded with the nested keys Stripe expects")
    void checkoutSessionIsFormEncoded() {
        stripe.on("POST /checkout/sessions", """
                {"id":"cs_test_1","url":"https://checkout.stripe.com/c/pay/cs_test_1"}""");

        PaymentProvider.Charge charge = provider.charge(request("USD", "12.50"));

        FakeGateway.Call call = stripe.call("/checkout/sessions");
        assertThat(call.header("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(call.header("Authorization")).isEqualTo("Bearer sk_test_stripe");

        String body = decodedBody("/checkout/sessions");
        // Minor units: 12.50 dollars is 1250 cents. A major-unit figure here
        // charges a hundredth of the price.
        assertThat(body).contains("line_items[0][price_data][unit_amount]=1250");
        assertThat(body).contains("line_items[0][price_data][currency]=usd");
        assertThat(body).contains("client_reference_id=HS-5");
        assertThat(charge.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
    }

    @Test
    @DisplayName("A zero-decimal currency is not multiplied by a hundred")
    void zeroDecimalCurrency() {
        stripe.on("POST /checkout/sessions", """
                {"id":"cs_1","url":"https://x"}""");

        provider.charge(request("XOF", "150"));

        // Stripe's own list has always been complete; this pins it so a later
        // edit cannot quietly shorten it the way Paystack's was short.
        assertThat(decodedBody("/checkout/sessions"))
                .contains("line_items[0][price_data][unit_amount]=150");
    }

    @Test
    @DisplayName("Charging a saved card says the customer is not present")
    void offSessionChargeCarriesTheRightFlags() {
        stripe.on("POST /payment_intents", """
                {"id":"pi_1","status":"succeeded"}""");

        PaymentProvider.Charge charge = provider.chargeStored(
                "cus_123/pm_456", request("USD", "12.50"));

        String body = decodedBody("/payment_intents");
        assertThat(body).contains("customer=cus_123");
        assertThat(body).contains("payment_method=pm_456");
        // Without confirm and off_session the intent is created and never
        // charged -- the renewal silently does nothing.
        assertThat(body).contains("confirm=true");
        assertThat(body).contains("off_session=true");
        // Redirects cannot be completed by a customer who is asleep.
        assertThat(body).contains("automatic_payment_methods[allow_redirects]=never");
        assertThat(body).contains("amount=1250");
        assertThat(charge.checkoutUrl()).as("nobody to send anywhere").isNull();
        assertThat(charge.providerRef()).isEqualTo("pi_1");
    }

    @Test
    @DisplayName("An idempotency key stops a retry charging twice")
    void offSessionChargeIsIdempotent() {
        stripe.on("POST /payment_intents", """
                {"id":"pi_1","status":"succeeded"}""");

        provider.chargeStored("cus_123/pm_456", request("USD", "12.50"));

        assertThat(stripe.call("/payment_intents").header("Idempotency-Key"))
                .isEqualTo("HS-5");
    }

    @Test
    @DisplayName("A card that wants the customer present is a decline, not a success")
    void requiresActionIsADecline() {
        stripe.on("POST /payment_intents", """
                {"id":"pi_2","status":"requires_action"}""");

        // 3DS at two in the morning cannot be completed by anyone. Treating this
        // as success issues a month of internet for nothing.
        assertThatThrownBy(() -> provider.chargeStored("cus_1/pm_1", request("USD", "12.50")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs the customer");
    }

    @Test
    @DisplayName("A stored token missing its customer is refused before any request")
    void halfATokenIsRefused() {
        assertThatThrownBy(() -> provider.chargeStored("pm_alone", request("USD", "12.50")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stripe.calls()).as("nothing should have been sent").isEmpty();
    }

    @Test
    @DisplayName("Stripe refusing the session is surfaced")
    void refusalSurfaces() {
        stripe.on("POST /checkout/sessions", 400, """
                {"error":{"message":"No such customer"}}""");

        assertThatThrownBy(() -> provider.charge(request("USD", "12.50")))
                .isInstanceOf(IllegalStateException.class);
    }
}
