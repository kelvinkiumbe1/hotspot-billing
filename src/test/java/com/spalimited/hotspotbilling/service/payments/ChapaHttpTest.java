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
 * Chapa over a real socket.
 *
 * <p>Chapa keys everything on the {@code tx_ref} we choose rather than an id of
 * its own, so the verify call has to quote it back exactly — and it takes birr
 * in major units, unlike the card processors it otherwise resembles.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChapaHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    private FakeGateway chapa;
    private ChapaProvider provider;

    @BeforeEach
    void setUp() {
        chapa = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "chapa", chapa.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.CHAPA)
                .active(true)
                .secretKey("CHASECK_TEST-abc")
                .webhookSecret("chapa-whsec")
                .build()));

        provider = new ChapaProvider(gateways, new ObjectMapper(), endpoints);
    }

    @AfterEach
    void tearDown() {
        chapa.close();
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "251911234567", null, new BigDecimal("75"), "ETB", "HS-21", "1 hour of WiFi");
    }

    @Test
    @DisplayName("Birr go in major units, and the reference is ours")
    void majorUnitsAndReference() {
        chapa.on("POST /transaction/initialize", """
                {"status":"success","data":{"checkout_url":"https://checkout.chapa.co/x"}}""");

        PaymentProvider.Charge charge = provider.charge(request());

        String body = chapa.call("/transaction/initialize").body();
        // 75, not 7500. Chapa quotes birr whole.
        assertThat(body).containsPattern("\"amount\":\"?75(\\.0+)?\"?[^0-9]");
        assertThat(body).contains("\"currency\":\"ETB\"");
        assertThat(body).contains("\"tx_ref\":\"HS-21\"");
        // Chapa has no id of its own, so ours has to be the handle.
        assertThat(charge.providerRef()).isEqualTo("HS-21");
        assertThat(charge.checkoutUrl()).isEqualTo("https://checkout.chapa.co/x");
    }

    @Test
    @DisplayName("A customer with no email still gets a payment")
    void derivedEmailDoesNotBlockTheSale() {
        chapa.on("POST /transaction/initialize", """
                {"status":"success","data":{"checkout_url":"https://x"}}""");

        provider.charge(request());

        // Chapa requires an email and a hotspot customer has a phone number.
        // Refusing the sale over it would be the wrong trade.
        String body = chapa.call("/transaction/initialize").body();
        assertThat(body).contains("@");
        assertThat(body).contains("\"phone_number\":\"251911234567\"");
    }

    @Test
    @DisplayName("The secret key authorises the request")
    void authHeader() {
        chapa.on("POST /transaction/initialize", """
                {"status":"success","data":{"checkout_url":"https://x"}}""");

        provider.charge(request());

        assertThat(chapa.call("/transaction/initialize").header("Authorization"))
                .isEqualTo("Bearer CHASECK_TEST-abc");
    }

    @Test
    @DisplayName("Verify quotes our reference back and reads the verdict")
    void verifyUsesOurReference() {
        chapa.on("GET /transaction/verify/HS-21", """
                {"status":"success","data":{"status":"success","amount":"75",
                 "currency":"ETB","tx_ref":"HS-21","reference":"CH-9"}}""");

        var settled = provider.poll("HS-21");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().amount()).isEqualByComparingTo(new BigDecimal("75"));
    }

    @Test
    @DisplayName("A payment still pending at Chapa is not called failed")
    void pendingIsNotAFailure() {
        chapa.on("GET /transaction/verify/HS-22", """
                {"status":"success","data":{"status":"pending","amount":"75","tx_ref":"HS-22"}}""");

        // Calling this a failure cancels a sale from a customer mid-payment.
        var settled = provider.poll("HS-22");
        assertThat(settled.isEmpty() || !settled.get().paid()).isTrue();
    }

    @Test
    @DisplayName("Chapa refusing is surfaced with its message")
    void refusalSurfaces() {
        chapa.on("POST /transaction/initialize", """
                {"status":"failed","message":"Invalid API Key"}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid API Key");
    }

    @Test
    @DisplayName("An HTTP failure reaches the customer as something readable")
    void httpErrorIsHandled() {
        chapa.on("POST /transaction/initialize", 503, "service unavailable");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Please try again");
    }
}
