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

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Wave over a real socket.
 *
 * <p>Wave takes the amount as a <em>string</em> in whole XOF. A number, or a
 * minor-unit figure, is either rejected or charges a hundred times the price,
 * and only the request itself shows which went out.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WaveHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    @Mock
    private PortalSettingsService portalSettings;

    private FakeGateway wave;
    private WaveProvider provider;

    @BeforeEach
    void setUp() {
        wave = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "wave", wave.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.WAVE)
                .active(true)
                .secretKey("wave_sn_prod_key")
                .webhookSecret("wave_whsec")
                .build()));
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country("SN").currencyCode("XOF").build());

        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null, "https://isp.example.net/api/payments/mpesa/callback", null));

        provider = new WaveProvider(gateways, portalSettings, urls, endpoints, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wave.close();
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "221771234567", null, new BigDecimal("500"), "XOF", "HS-13", "1 hour of WiFi");
    }

    @Test
    @DisplayName("The amount is a string in whole XOF")
    void amountIsAStringInWholeUnits() {
        wave.on("POST /v1/checkout/sessions", """
                {"id":"cos-1","wave_launch_url":"https://pay.wave.com/c/cos-1",
                 "checkout_status":"open","payment_status":"processing"}""");

        provider.charge(request());

        String body = wave.call("/v1/checkout/sessions").body();
        // Quoted, and 500 rather than 50000. Wave rejects a bare number, and a
        // minor-unit figure would take a hundred times the price.
        assertThat(body).contains("\"amount\":\"500\"");
        assertThat(body).contains("\"currency\":\"XOF\"");
        assertThat(body).contains("\"client_reference\":\"HS-13\"");
    }

    @Test
    @DisplayName("The API key authorises and a retry cannot open a second checkout")
    void authAndIdempotency() {
        wave.on("POST /v1/checkout/sessions", """
                {"id":"cos-1","wave_launch_url":"https://x"}""");

        provider.charge(request());

        FakeGateway.Call call = wave.call("/v1/checkout/sessions");
        assertThat(call.header("Authorization")).isEqualTo("Bearer wave_sn_prod_key");
        assertThat(call.header("Idempotency-Key")).isEqualTo("HS-13");
    }

    @Test
    @DisplayName("The customer is sent back to the portal, not left on Wave")
    void returnUrlsPointAtUs() {
        wave.on("POST /v1/checkout/sessions", """
                {"id":"cos-1","wave_launch_url":"https://x"}""");

        provider.charge(request());

        String body = wave.call("/v1/checkout/sessions").body();
        assertThat(body).contains("https://isp.example.net/?paid=");
        assertThat(body).contains("https://isp.example.net/?failed=");
    }

    @Test
    @DisplayName("The launch URL is what the customer opens")
    void launchUrlIsReturned() {
        wave.on("POST /v1/checkout/sessions", """
                {"id":"cos-77","wave_launch_url":"https://pay.wave.com/c/cos-77"}""");

        PaymentProvider.Charge charge = provider.charge(request());

        assertThat(charge.providerRef()).isEqualTo("cos-77");
        assertThat(charge.checkoutUrl()).isEqualTo("https://pay.wave.com/c/cos-77");
    }

    @Test
    @DisplayName("A session lookup distinguishes the page from the money")
    void pollSeparatesPageFromPayment() {
        wave.on("GET /v1/checkout/sessions/cos-1", """
                {"id":"cos-1","client_reference":"HS-13","amount":"500","currency":"XOF",
                 "payment_status":"succeeded","checkout_status":"complete","transaction_id":"T-1"}""");
        var paid = provider.poll("cos-1");
        assertThat(paid).isPresent();
        assertThat(paid.get().paid()).isTrue();

        // Page finished, money still moving. Reading the page as the money
        // issues a voucher for nothing.
        wave.on("GET /v1/checkout/sessions/cos-2", """
                {"id":"cos-2","amount":"500","payment_status":"processing",
                 "checkout_status":"complete"}""");
        assertThat(provider.poll("cos-2")).isEmpty();
    }

    @Test
    @DisplayName("Wave refusing is surfaced")
    void refusalSurfaces() {
        wave.on("POST /v1/checkout/sessions", 401, """
                {"message":"invalid api key"}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A currency that is not the market's stops the charge locally")
    void currencyGuard() {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country("SN").currencyCode("KES").build());

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(wave.calls()).as("nothing should have been sent").isEmpty();
    }
}
