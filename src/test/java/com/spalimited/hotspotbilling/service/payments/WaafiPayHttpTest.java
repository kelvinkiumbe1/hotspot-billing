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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WaafiPay over a real socket.
 *
 * <p>The response bodies here are not invented. They are the shapes the live API
 * returned when probed: the missing-parameter error, the not-authorized error,
 * and the unknown-service error that proved there is no status endpoint to ask.
 *
 * <p>Which makes one thing load-bearing above all others. <b>Every response is
 * HTTP 200</b>, including every failure — so {@link WaafiPayProvider#approved} is
 * the only thing standing between a declined payment and a free voucher.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WaafiPayHttpTest {

    private static final String MERCHANT = "M0910291";
    private static final String API_USER = "1000416";
    private static final String API_KEY = "API-675418888AHX";

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private FakeGateway waafi;
    private WaafiPayProvider provider;

    @BeforeEach
    void setUp() {
        waafi = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "waafipay", waafi.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.WAAFIPAY)
                .active(true)
                .shortCode(MERCHANT)
                .consumerKey(API_USER)
                .secretKey(API_KEY)
                .build()));
        country("SO", "USD");
        provider = new WaafiPayProvider(gateways, portalSettings, endpoints);
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    @AfterEach
    void tearDown() {
        waafi.close();
    }

    /** The live API answers everything on one path with no trailing segment. */
    private static final String PATH = "/";

    private void purchaseApproved() {
        waafi.on("POST " + PATH, """
                {"schemaVersion":"1.0","timestamp":"2026-08-19 14:38:58.837",
                 "responseId":"c1d2e3","responseCode":"2001","errorCode":"0",
                 "responseMsg":"RCS_SUCCESS",
                 "params":{"state":"APPROVED","transactionId":"7264827","referenceId":"HS-51",
                           "txAmount":"1.50","issuerTransactionId":"EVC1234"}}""");
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "252611234567", null, new BigDecimal("1.50"), "USD", "HS-51",
                "1 hour of WiFi");
    }

    // ------------------------------------------------------- the request shape

    @Test
    @DisplayName("The request is the shape the live API accepts")
    void requestShape() {
        purchaseApproved();

        provider.charge(request());

        // Every field here was confirmed understood by the real API: a shaped
        // request with fake credentials got past validation to authorization,
        // which it could not have done with a field named wrongly.
        String body = waafi.call(PATH).body();
        assertThat(body).contains("\"schemaVersion\":\"1.0\"");
        assertThat(body).contains("\"serviceName\":\"API_PURCHASE\"");
        assertThat(body).contains("\"channelName\":\"WEB\"");
        assertThat(body).contains("\"merchantUid\":\"" + MERCHANT + "\"");
        assertThat(body).contains("\"apiUserId\":\"" + API_USER + "\"");
        assertThat(body).contains("\"apiKey\":\"" + API_KEY + "\"");
        assertThat(body).contains("\"paymentMethod\":\"MWALLET_ACCOUNT\"");
        assertThat(body).contains("\"referenceId\":\"HS-51\"");
        assertThat(body).contains("\"currency\":\"USD\"");
    }

    @Test
    @DisplayName("A timestamp is always sent, because its absence is the first refusal")
    void timestampIsAlwaysSent() {
        purchaseApproved();

        provider.charge(request());

        // The live API's answer to a request without one:
        // 5032 / E10017 "Your request is missing (timestamp) parameter".
        // And it is not ISO-8601 -- "2026-08-19 14:38:58.837".
        assertThat(waafi.call(PATH).body())
                .containsPattern("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\"");
    }

    @Test
    @DisplayName("The number goes international, digits only")
    void msisdnIsInternational() {
        purchaseApproved();

        provider.charge(new PaymentProvider.ChargeRequest(
                "+252 61 123 4567", null, new BigDecimal("1.50"), "USD", "HS-51", "WiFi"));

        assertThat(waafi.call(PATH).body()).contains("\"accountNo\":\"252611234567\"");
    }

    @Test
    @DisplayName("Dollars go in major units")
    void amountIsMajorUnits() {
        purchaseApproved();

        provider.charge(request());

        // 1.50 dollars, not 150 cents. EVC Plus prices in dollars.
        assertThat(waafi.call(PATH).body()).contains("\"amount\":\"1.50\"");
    }

    // ---------------------------------------------- settled where it is made

    @Test
    @DisplayName("An approved purchase comes back already settled")
    void approvedPurchaseSettlesItself() {
        purchaseApproved();

        PaymentProvider.Charge charge = provider.charge(request());

        // There is no webhook and no status service, so this is the only chance
        // this payment gets. Without settledNow it would sit pending until the
        // sweep failed a customer who had paid.
        assertThat(charge.settledNow()).isNotNull();
        assertThat(charge.settledNow().paid()).isTrue();
        assertThat(charge.settledNow().reference()).isEqualTo("HS-51");
        assertThat(charge.settledNow().receipt()).isEqualTo("7264827");
        assertThat(charge.settledNow().amount()).isEqualByComparingTo(new BigDecimal("1.50"));
        // No page: they approved it on their handset.
        assertThat(charge.checkoutUrl()).isNull();
    }

    // --------------------------------------------- HTTP 200 on every failure

    @Test
    @DisplayName("A refusal arrives as HTTP 200 and is still a refusal")
    void notAuthorizedIsRefused() {
        // Verbatim from the live API, with fake credentials. Note the 200.
        waafi.on("POST " + PATH, 200, """
                {"schemaVersion":"1.0","timestamp":"2026-08-19 14:38:58.837",
                 "responseId":"zidi-probe-1","responseCode":"5010","errorCode":"E10015",
                 "responseMsg":"You are not authorized to access the requested service"}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("A missing parameter arrives as HTTP 200 and is still a refusal")
    void missingParameterIsRefused() {
        waafi.on("POST " + PATH, 200, """
                {"schemaVersion":"1.0","timestamp":"2026-08-19 14:38:57.867","responseId":"",
                 "responseCode":"5032","errorCode":"E10017",
                 "responseMsg":"Your request is missing (timestamp) parameter. Please check again"}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing (timestamp)");
    }

    @Test
    @DisplayName("A declined wallet is a refusal, not a sale")
    void declinedWalletIsRefused() {
        waafi.on("POST " + PATH, 200, """
                {"schemaVersion":"1.0","responseCode":"5206","errorCode":"E10205",
                 "responseMsg":"Insufficient balance"}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient balance");
    }

    @Test
    @DisplayName("Only both codes together mean the money moved")
    void approvalNeedsEveryPart() {
        // This is the method that gives internet away if it is too generous.
        assertThat(WaafiPayProvider.approved(null)).isFalse();
        assertThat(read("{\"responseCode\":\"2001\",\"errorCode\":\"0\"}")).isTrue();
        // The right response code with an error beside it is not a success.
        assertThat(read("{\"responseCode\":\"2001\",\"errorCode\":\"E10205\"}")).isFalse();
        // Nor is a clean error code under the wrong response code.
        assertThat(read("{\"responseCode\":\"5010\",\"errorCode\":\"0\"}")).isFalse();
        // Nor a success envelope over a transaction that was not approved.
        assertThat(read("{\"responseCode\":\"2001\",\"errorCode\":\"0\","
                + "\"params\":{\"state\":\"DECLINED\"}}")).isFalse();
        assertThat(read("{\"responseCode\":\"2001\",\"errorCode\":\"0\","
                + "\"params\":{\"state\":\"APPROVED\"}}")).isTrue();
        // And an empty body is not a payment.
        assertThat(read("{}")).isFalse();
    }

    private static boolean read(String json) {
        return WaafiPayProvider.approved(
                new tools.jackson.databind.ObjectMapper().readTree(json));
    }

    // ------------------------------------------------------- no way to ask again

    @Test
    @DisplayName("Nothing about this rail claims to be pollable")
    void notPollable() {
        // Its own API confirms there is no status service: API_PURCHASE and
        // API_PREAUTHORIZE are recognised, and every other name -- including one
        // invented as a control -- answers E10309 Bad Request.
        assertThat(provider.pollable()).isFalse();
        assertThat(provider.poll("7264827")).isEmpty();
    }

    @Test
    @DisplayName("Nothing may settle this rail by posting at it")
    void thereIsNoWebhook() {
        assertThatThrownBy(() -> provider.settle("{}".getBytes(StandardCharsets.UTF_8), Map.of()))
                .hasMessageContaining("no webhook");
    }

    @Test
    @DisplayName("An unanswered purchase is surfaced, not parked")
    void anUnansweredPurchaseThrows() {
        // No route registered, so the fake gateway answers 418 with a body that
        // is not a WaafiPay envelope. Every other rail here would leave this for
        // the sweep; there is nothing to sweep with, so the customer is told.
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ market

    @Test
    @DisplayName("Outside Somalia it is not offered")
    void outsideSomaliaItIsNotOffered() {
        country("KE", "KES");

        assertThat(provider.usable()).isFalse();
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(waafi.calls()).isEmpty();
    }

    @Test
    @DisplayName("Prices have to be in dollars, because that is what EVC Plus takes")
    void currencyMustAgree() {
        country("SO", "KES");

        assertThat(provider.usable()).isFalse();
        assertThat(waafi.calls()).isEmpty();
    }
}
