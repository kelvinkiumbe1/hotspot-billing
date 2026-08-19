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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Flutterwave over a real socket.
 *
 * <p>Its unit convention is the opposite of Paystack's — major units, not minor
 * — and the two live in the same package doing the same job. Getting them the
 * same way round charges a hundred times the price or a hundredth of it, and
 * nothing but an actual request reveals which one went out.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlutterwaveHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    private FakeGateway flutterwave;
    private FlutterwaveProvider provider;

    @BeforeEach
    void setUp() {
        flutterwave = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "flutterwave", flutterwave.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.FLUTTERWAVE)
                .active(true)
                .secretKey("FLWSECK-abc")
                .webhookSecret("hash-secret")
                .build()));

        provider = new FlutterwaveProvider(gateways, new ObjectMapper(), endpoints);
    }

    @AfterEach
    void tearDown() {
        flutterwave.close();
    }

    private static PaymentProvider.ChargeRequest request(String currency, String amount) {
        return new PaymentProvider.ChargeRequest(
                "254712345678", null, new BigDecimal(amount), currency, "HS-7", "1 hour of WiFi");
    }

    @Test
    @DisplayName("The amount goes in major units, unlike Paystack in the same package")
    void amountIsMajorUnits() {
        flutterwave.on("POST /payments", """
                {"status":"success","data":{"link":"https://checkout.flutterwave.com/x"}}""");

        provider.charge(request("KES", "150"));

        String body = flutterwave.call("/payments").body();
        // 150, not 15000. Copying Paystack's convention here would take a
        // hundred times the price.
        assertThat(body).containsPattern("\"amount\":\"?150(\\.0+)?\"?[^0-9]");
        assertThat(body).contains("\"currency\":\"KES\"");
        assertThat(body).contains("\"tx_ref\":\"HS-7\"");
    }

    @Test
    @DisplayName("The secret key authorises the request")
    void authHeader() {
        flutterwave.on("POST /payments", """
                {"status":"success","data":{"link":"https://x"}}""");

        provider.charge(request("KES", "150"));

        assertThat(flutterwave.call("/payments").header("Authorization"))
                .isEqualTo("Bearer FLWSECK-abc");
    }

    @Test
    @DisplayName("A checkout link is returned for the customer to open")
    void returnsTheCheckoutLink() {
        flutterwave.on("POST /payments", """
                {"status":"success","data":{"link":"https://checkout.flutterwave.com/pay/abc"}}""");

        PaymentProvider.Charge charge = provider.charge(request("KES", "150"));

        assertThat(charge.checkoutUrl()).isEqualTo("https://checkout.flutterwave.com/pay/abc");
        assertThat(charge.providerRef()).isEqualTo("HS-7");
    }

    @Test
    @DisplayName("Flutterwave refusing is surfaced with its own message")
    void refusalSurfaces() {
        flutterwave.on("POST /payments", """
                {"status":"error","message":"Invalid authorization key"}""");

        assertThatThrownBy(() -> provider.charge(request("KES", "150")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid authorization key");
    }

    @Test
    @DisplayName("An HTTP failure does not leak a stack trace at a customer")
    void httpErrorIsHandled() {
        flutterwave.on("POST /payments", 502, "<html>bad gateway</html>");

        assertThatThrownBy(() -> provider.charge(request("KES", "150")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Please try again");
    }

    @Test
    @DisplayName("A zero-decimal currency is still sent whole")
    void zeroDecimalStaysWhole() {
        flutterwave.on("POST /payments", """
                {"status":"success","data":{"link":"https://x"}}""");

        // Major units for every currency, so XOF needs no special case here --
        // asserted so that a later "fix" copying Paystack's minor-unit maths
        // cannot land without failing.
        provider.charge(request("XOF", "150"));

        assertThat(flutterwave.call("/payments").body())
                .containsPattern("\"amount\":\"?150(\\.0+)?\"?[^0-9]");
    }
}
