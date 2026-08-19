package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.config.MpesaProperties;
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

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Paynow over a real socket.
 *
 * <p>Paynow speaks form-encoded key-value pairs rather than JSON, in both
 * directions, and every message is hashed over its fields in a fixed order. The
 * thing this catches that nothing else could: where Paynow is told to send the
 * customer back to, and where to post the result — both of which were pointing
 * at Paynow itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaynowHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    @Mock
    private com.spalimited.hotspotbilling.service.PortalSettingsService portalSettings;

    private FakeGateway paynow;
    private PaynowProvider provider;

    @BeforeEach
    void setUp() {
        paynow = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "paynow", paynow.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.PAYNOW)
                .active(true)
                .consumerKey("12345")                 // integration id
                .secretKey("integration-key-abc")     // integration key
                .build()));

        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null, "https://isp.example.net/api/payments/mpesa/callback", null));

        when(portalSettings.settings()).thenReturn(
                com.spalimited.hotspotbilling.domain.PortalSettings.builder()
                        .country("ZW").currencyCode("USD").build());
        provider = new PaynowProvider(gateways, portalSettings, endpoints, urls);
    }

    @AfterEach
    void tearDown() {
        paynow.close();
    }

    private static PaymentProvider.ChargeRequest request(String phone) {
        return new PaymentProvider.ChargeRequest(
                phone, "buyer@example.com", new BigDecimal("2"), "USD", "HS-31", "1 hour of WiFi");
    }

    private String decoded(String path) {
        return URLDecoder.decode(paynow.call(path).body(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Paynow is told to send the customer and the result back to us")
    void callbackUrlsPointAtUs() {
        paynow.on("POST /interface/remotetransaction",
                "status=ok&pollurl=" + paynow.url() + "/poll/1&browserurl=https://paynow/x");

        provider.charge(request("263771234567"));

        String body = decoded("/interface/remotetransaction");
        // These were both paynow.co.zw. resulturl is where the outcome is
        // POSTed, so the callback went to Paynow and never arrived here.
        assertThat(body).contains("resulturl=https://isp.example.net/api/payments/paynow/webhook");
        assertThat(body).contains("returnurl=https://isp.example.net/?paid=");
        assertThat(body).doesNotContain("resulturl=https://www.paynow.co.zw");
    }

    @Test
    @DisplayName("A known wallet takes Express Checkout, which prompts the handset")
    void expressCheckoutWhenThePhoneIsKnown() {
        paynow.on("POST /interface/remotetransaction",
                "status=ok&pollurl=" + paynow.url() + "/poll/1");

        PaymentProvider.Charge charge = provider.charge(request("263771234567"));

        String body = decoded("/interface/remotetransaction");
        assertThat(body).contains("method=ecocash");
        assertThat(body).contains("phone=263771234567");
        // Express prompts the phone; there is no page to open.
        assertThat(charge.checkoutUrl()).isNull();
        // The poll URL is the handle, since Paynow has no id of its own.
        assertThat(charge.providerRef()).contains("/poll/1");
    }

    @Test
    @DisplayName("With no phone it falls back to the redirect flow")
    void redirectFlowWithoutAPhone() {
        paynow.on("POST /interface/initiatetransaction",
                "status=ok&pollurl=" + paynow.url() + "/poll/2&browserurl=https://paynow.co.zw/pay/2");

        PaymentProvider.Charge charge = provider.charge(request(null));

        assertThat(charge.checkoutUrl()).isEqualTo("https://paynow.co.zw/pay/2");
        assertThat(paynow.calls()).noneMatch(c -> c.path().contains("remotetransaction"));
    }

    @Test
    @DisplayName("The amount goes with two decimals and the hash covers the fields")
    void amountAndHash() {
        paynow.on("POST /interface/remotetransaction",
                "status=ok&pollurl=" + paynow.url() + "/poll/1");

        provider.charge(request("263771234567"));

        String body = decoded("/interface/remotetransaction");
        assertThat(body).contains("amount=2.00");
        assertThat(body).contains("id=12345");
        assertThat(body).contains("reference=HS-31");
        // A hash is present and is not empty -- Paynow rejects a message
        // without one, with an error that explains nothing.
        assertThat(body).containsPattern("hash=[0-9A-Fa-f]{32,}");
    }

    @Test
    @DisplayName("Paynow refusing is surfaced with its own error")
    void refusalSurfaces() {
        paynow.on("POST /interface/remotetransaction",
                "status=error&error=Invalid+integration+id");

        assertThatThrownBy(() -> provider.charge(request("263771234567")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    @DisplayName("Accepted but with no poll URL is refused rather than left unfindable")
    void acceptedWithoutAPollUrlIsRefused() {
        paynow.on("POST /interface/remotetransaction", "status=ok");

        // Paynow has no id of its own; without the poll URL the payment can
        // never be asked about again.
        assertThatThrownBy(() -> provider.charge(request("263771234567")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no way to check it");
    }

    @Test
    @DisplayName("Without a public address the charge never starts")
    void refusesWhenThereIsNowhereToCallBack() {
        PublicUrls none = new PublicUrls(new MpesaProperties(
                null, null, null, null, null, "https://example.com/api/payments/mpesa/callback", null));
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "paynow", paynow.url());
        PaynowProvider stranded = new PaynowProvider(gateways, portalSettings, endpoints, none);

        // Starting it anyway leaves the customer on Paynow's site having paid,
        // with no result ever posted back.
        assertThatThrownBy(() -> stranded.charge(request("263771234567")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public address");
        assertThat(paynow.calls()).isEmpty();
    }
}
