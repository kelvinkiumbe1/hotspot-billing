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
 * Paystack over a real socket.
 *
 * <p>Everything here was previously untested. The existing Paystack test calls
 * the parsing helpers on JSON somebody typed by hand, which proves the parser
 * and says nothing about the request that produced it — the authorization
 * header, the minor-unit conversion, the channel list, or what happens when
 * Paystack says no.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaystackHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    @Mock
    private com.spalimited.hotspotbilling.service.PortalSettingsService portalSettings;

    private FakeGateway paystack;
    private PaystackProvider provider;

    @BeforeEach
    void setUp() {
        paystack = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "paystack", paystack.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.PAYSTACK)
                .active(true)
                .secretKey("sk_test_abc123")
                .build()));

        // Kenya is one of Paystack's markets. Outside them the rail is not
        // offered at all, which is the gate these tests must sit inside.
        when(portalSettings.settings()).thenReturn(
                com.spalimited.hotspotbilling.domain.PortalSettings.builder()
                        .country("KE").currencyCode("KES").build());
        provider = new PaystackProvider(gateways, portalSettings, new ObjectMapper(), endpoints);
    }

    @AfterEach
    void tearDown() {
        paystack.close();
    }

    private static PaymentProvider.ChargeRequest request(String currency, String amount) {
        return new PaymentProvider.ChargeRequest(
                "254712345678", null, new BigDecimal(amount), currency, "HS-42", "1 hour of WiFi");
    }

    @Test
    @DisplayName("A charge reaches the right endpoint with the right auth and amount")
    void chargeSendsAWellFormedRequest() {
        paystack.on("POST /transaction/initialize", """
                {"status":true,"data":{"reference":"HS-42",
                 "authorization_url":"https://checkout.paystack.com/abc"}}""");

        PaymentProvider.Charge charge = provider.charge(request("KES", "150"));

        FakeGateway.Call call = paystack.call("/transaction/initialize");
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.header("Authorization")).isEqualTo("Bearer sk_test_abc123");
        // Minor units. Paystack takes kobo/cents, so 150 must go as 15000 --
        // send the major figure and every customer is charged a hundredth.
        assertThat(call.bodyContains("\"amount\":15000")).isTrue();
        assertThat(call.bodyContains("\"currency\":\"KES\"")).isTrue();
        assertThat(call.bodyContains("\"reference\":\"HS-42\"")).isTrue();
        assertThat(charge.checkoutUrl()).isEqualTo("https://checkout.paystack.com/abc");
    }

    @Test
    @DisplayName("Mobile money leads the checkout in a market that uses it")
    void channelsPutMobileMoneyFirst() {
        paystack.on("POST /transaction/initialize", """
                {"status":true,"data":{"reference":"HS-42","authorization_url":"https://x"}}""");

        provider.charge(request("KES", "150"));

        // A customer opening a page that asks for a 16-digit card number in a
        // market where almost nobody has one concludes they cannot pay.
        String body = paystack.call("/transaction/initialize").body();
        int mobile = body.indexOf("mobile_money");
        int card = body.indexOf("\"card\"");
        assertThat(mobile).as("mobile money is offered at all").isGreaterThan(-1);
        assertThat(mobile).as("and before card, in KES").isLessThan(card);
    }

    @Test
    @DisplayName("Paystack refusing is reported, not swallowed")
    void refusalSurfaces() {
        paystack.on("POST /transaction/initialize", """
                {"status":false,"message":"Invalid key"}""");

        // A customer who pressed Pay has to be told something; a silent failure
        // leaves them waiting for a callback that will never come.
        assertThatThrownBy(() -> provider.charge(request("KES", "150")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid key");
    }

    @Test
    @DisplayName("An HTTP error is turned into something a customer can read")
    void httpErrorIsHandled() {
        paystack.on("POST /transaction/initialize", 500, "{\"message\":\"boom\"}");

        assertThatThrownBy(() -> provider.charge(request("KES", "150")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("500");
    }

    @Test
    @DisplayName("A zero-decimal currency is not multiplied by a hundred")
    void zeroDecimalCurrency() {
        paystack.on("POST /transaction/initialize", """
                {"status":true,"data":{"reference":"HS-42","authorization_url":"https://x"}}""");

        // XOF has no minor unit. Sending 15000 for a 150 charge would take a
        // hundred times the price from a customer in Senegal.
        provider.charge(request("XOF", "150"));

        String body = paystack.call("/transaction/initialize").body();
        assertThat(body).contains("\"currency\":\"XOF\"");
        // Exact, not a substring. contains("amount":150) also matches
        // "amount":15000, which is how this assertion passed while the bug it
        // was written to catch was live.
        assertThat(body)
                .as("XOF is zero-decimal, so the amount must go as typed")
                .containsPattern("\"amount\":150[^0-9]");
    }
}
