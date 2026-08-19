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

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Airtel over a real socket.
 *
 * <p>The rail with the worst record in this project: three separate defects,
 * every one of which shipped. The one thing its unit tests could not reach is
 * the request itself — and the single most consequential line in that class
 * strips the dialling code off the customer's number, because Airtel accepts a
 * charge to a full international number and then cannot find the subscriber.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AirtelHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    @Mock
    private PortalSettingsService portalSettings;

    private FakeGateway airtel;
    private AirtelProvider provider;

    @BeforeEach
    void setUp() {
        airtel = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "airtelSandbox", airtel.url());
        ReflectionTestUtils.setField(endpoints, "airtelProduction", airtel.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.AIRTEL_MONEY)
                .active(true)
                .environment(PaymentGateway.Environment.PRODUCTION)
                .consumerKey("airtel-client-id")
                .consumerSecret("airtel-client-secret")
                .build()));
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country("KE").currencyCode("KES").build());

        provider = new AirtelProvider(gateways, endpoints, portalSettings);

        airtel.on("POST /auth/oauth2/token", """
                {"access_token":"tok-air","token_type":"Bearer","expires_in":"3600"}""");
    }

    @AfterEach
    void tearDown() {
        airtel.close();
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "254712345678", null, new BigDecimal("50"), "KES", "HS-3", "1 hour of WiFi");
    }

    @Test
    @DisplayName("The dialling code comes off before the number reaches Airtel")
    void msisdnLosesTheCountryCode() {
        airtel.on("POST /merchant/v1/payments/", """
                {"status":{"success":true,"code":"200"}}""");

        provider.charge(request());

        String body = airtel.call("/merchant/v1/payments/").body();
        // 712345678, not 254712345678. Send the full number and Airtel accepts
        // the request and then never finds the subscriber -- a charge that looks
        // sent and simply never arrives.
        assertThat(body).contains("\"msisdn\":\"712345678\"");
        assertThat(body).doesNotContain("254712345678");
    }

    @Test
    @DisplayName("Country and currency headers accompany the charge")
    void marketHeaders() {
        airtel.on("POST /merchant/v1/payments/", """
                {"status":{"success":true,"code":"200"}}""");

        provider.charge(request());

        FakeGateway.Call call = airtel.call("/merchant/v1/payments/");
        assertThat(call.header("X-Country")).isEqualTo("KE");
        assertThat(call.header("X-Currency")).isEqualTo("KES");
        assertThat(call.header("Authorization")).isEqualTo("Bearer tok-air");
    }

    @Test
    @DisplayName("The amount goes in whole units and the reference is ours")
    void amountAndReference() {
        airtel.on("POST /merchant/v1/payments/", """
                {"status":{"success":true,"code":"200"}}""");

        PaymentProvider.Charge charge = provider.charge(request());

        String body = airtel.call("/merchant/v1/payments/").body();
        assertThat(body).containsPattern("\"amount\":\"?50\"?[^0-9]");
        // Generated before the call, so a request that times out having reached
        // Airtel is still findable afterwards.
        assertThat(body).contains(charge.providerRef());
        assertThat(charge.checkoutUrl()).as("a USSD push has no page").isNull();
    }

    @Test
    @DisplayName("The token call sends the client id and secret as JSON, not Basic auth")
    void tokenShape() {
        airtel.on("POST /merchant/v1/payments/", """
                {"status":{"success":true,"code":"200"}}""");

        provider.charge(request());

        FakeGateway.Call token = airtel.call("/auth/oauth2/token");
        assertThat(token.body()).contains("\"client_id\":\"airtel-client-id\"");
        assertThat(token.body()).contains("\"client_secret\":\"airtel-client-secret\"");
        assertThat(token.body()).contains("client_credentials");
    }

    @Test
    @DisplayName("Airtel's own status object decides, not the HTTP code")
    void statusObjectDecides() {
        // 200 OK with success:false is a refusal. Reading the HTTP code would
        // treat a declined payment as accepted and leave it pending forever.
        airtel.on("POST /merchant/v1/payments/", 200, """
                {"status":{"success":false,"code":"401","message":"Invalid credentials"}}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    @DisplayName("An enquiry reports paid, and reports no amount because Airtel sends none")
    void pollReadsSuccess() {
        airtel.on("GET /standard/v1/payments/tx-1", """
                {"data":{"transaction":{"id":"tx-1","status":"TS","airtel_money_id":"AM-5"}}}""");

        var settled = provider.poll("tx-1");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().receipt()).isEqualTo("AM-5");
        // Null, not zero. Zero here is what marked every webhook-settled Airtel
        // payment FAILED with the customer already charged.
        assertThat(settled.get().amount()).isNull();
    }

    @Test
    @DisplayName("TA and TIP are still in progress and must not read as failures")
    void ambiguousStatesAreNotVerdicts() {
        airtel.on("GET /standard/v1/payments/tx-2", """
                {"data":{"transaction":{"id":"tx-2","status":"TA"}}}""");
        assertThat(provider.poll("tx-2")).isEmpty();

        airtel.on("GET /standard/v1/payments/tx-3", """
                {"data":{"transaction":{"id":"tx-3","status":"TIP"}}}""");
        assertThat(provider.poll("tx-3")).isEmpty();
    }

    @Test
    @DisplayName("A mismatched currency stops the charge before it leaves")
    void currencyGuardStopsIt() {
        // Airtel takes a bare number in the market's currency. Prices written in
        // a different one would be charged as that number of shillings-or-francs.
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country("KE").currencyCode("USD").build());

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(airtel.calls()).as("nothing should have been sent").isEmpty();
    }
}
