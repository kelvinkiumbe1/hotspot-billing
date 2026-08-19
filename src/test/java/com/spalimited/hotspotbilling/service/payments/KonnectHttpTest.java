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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Konnect over a real socket.
 *
 * <p>One thing here is unlike every other rail in this package and it is the
 * whole reason this file is worth reading: the dinar has a <em>thousand</em>
 * millimes. Every other minor-unit rail multiplies by a hundred, and doing that
 * here undercharges by a factor of ten on every sale without erroring once.
 *
 * <p>The other is that the callback is unsigned and normally arrives as a GET, so
 * nothing in it is believed — the status endpoint decides.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KonnectHttpTest {

    private static final String API_KEY = "6a1b2c3d4e5f60718293a4b5:konnectKeyTail";
    private static final String WALLET = "5f7a209dfc9c6a0021a4b3ce";

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private FakeGateway konnect;
    private KonnectProvider provider;

    @BeforeEach
    void setUp() {
        konnect = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "konnectSandbox", konnect.url());
        ReflectionTestUtils.setField(endpoints, "konnectProduction", konnect.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.KONNECT)
                .active(true)
                .environment(PaymentGateway.Environment.PRODUCTION)
                .secretKey(API_KEY)
                .shortCode(WALLET)
                .build()));
        country("TN", "TND");

        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://isp.example.tn/api/payments/mpesa/callback", null));
        provider = new KonnectProvider(gateways, portalSettings, endpoints, urls);
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    @AfterEach
    void tearDown() {
        konnect.close();
    }

    private void initSucceeds() {
        konnect.on("POST /payments/init-payment",
                "{\"payUrl\":\"https://gateway.konnect.network/pay?payment_ref=abc123\","
                + "\"paymentRef\":\"abc123\"}");
    }

    private static PaymentProvider.ChargeRequest request(String amount) {
        return new PaymentProvider.ChargeRequest(
                "21620123456", null, new BigDecimal(amount), "TND", "HS-88",
                "1 hour of WiFi");
    }

    // ------------------------------------------------------------- the dinar

    @Test
    @DisplayName("Ten dinars is ten thousand millimes, not one thousand")
    void theDinarHasAThousandMillimes() {
        initSucceeds();

        provider.charge(request("10"));

        // The hundred every other rail here uses would send 1000, and Konnect
        // would happily collect one dinar for a ten-dinar plan. Nothing errors;
        // the operator finds out from their settlement report.
        assertThat(konnect.call("/payments/init-payment").body())
                .containsPattern("\"amount\":10000[^0-9]");
    }

    @Test
    @DisplayName("Millimes are counted exactly, including the third decimal")
    void millimesAreExact() {
        assertThat(KonnectProvider.millimes(new BigDecimal("10"))).isEqualTo(10_000L);
        assertThat(KonnectProvider.millimes(new BigDecimal("1"))).isEqualTo(1_000L);
        assertThat(KonnectProvider.millimes(new BigDecimal("0.500"))).isEqualTo(500L);
        // Three decimal places are real money here, not rounding noise.
        assertThat(KonnectProvider.millimes(new BigDecimal("2.345"))).isEqualTo(2_345L);
        assertThat(KonnectProvider.millimes(new BigDecimal("12.750"))).isEqualTo(12_750L);
    }

    @Test
    @DisplayName("A settled amount is read back as dinars")
    void statusAmountComesBackAsDinars() {
        konnect.on("GET /payments/abc123",
                "{\"payment\":{\"id\":\"abc123\",\"status\":\"completed\",\"amount\":10000,"
                + "\"orderId\":\"HS-88\"}}");

        Optional<PaymentProvider.Settlement> settled = provider.poll("abc123");

        assertThat(settled).isPresent();
        // 10000 millimes is ten dinars. Not ten thousand, which is what a
        // receipt would say if this were read straight through.
        assertThat(settled.get().amount()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(settled.get().currency()).isEqualTo("TND");
    }

    // ------------------------------------------------------------------ charge

    @Test
    @DisplayName("The request names the wallet, the currency and our reference")
    void chargeBody() {
        initSucceeds();

        PaymentProvider.Charge charge = provider.charge(request("10"));

        String body = konnect.call("/payments/init-payment").body();
        assertThat(body).contains("\"receiverWalletId\":\"" + WALLET + "\"");
        assertThat(body).contains("\"token\":\"TND\"");
        // orderId is ours and is how a settlement finds its way to a payment.
        assertThat(body).contains("\"orderId\":\"HS-88\"");
        assertThat(charge.providerRef()).isEqualTo("abc123");
        assertThat(charge.checkoutUrl()).startsWith("https://gateway.konnect.network/pay");
    }

    @Test
    @DisplayName("The API key rides in x-api-key, not a bearer header")
    void authHeader() {
        initSucceeds();

        provider.charge(request("10"));

        // Not Authorization. A bearer header here is silently unauthenticated.
        assertThat(konnect.call("/payments/init-payment").header("x-api-key")).isEqualTo(API_KEY);
        assertThat(konnect.call("/payments/init-payment").header("Authorization")).isNull();
    }

    @Test
    @DisplayName("e-DINAR is offered, which is the point of using Konnect at all")
    void everyPaymentMethodIsOffered() {
        initSucceeds();

        provider.charge(request("10"));

        // The post office card. A great many Tunisians hold one and no
        // international processor touches it, so leaving it off would mean
        // integrating a domestic gateway and then not reaching the domestic
        // payment method.
        String body = konnect.call("/payments/init-payment").body();
        assertThat(body).contains("e-DINAR");
        assertThat(body).contains("wallet");
        assertThat(body).contains("bank_card");
    }

    @Test
    @DisplayName("Konnect is told where to call back")
    void callbackUrlPointsAtUs() {
        initSucceeds();

        provider.charge(request("10"));

        String body = konnect.call("/payments/init-payment").body();
        assertThat(body).contains("https://isp.example.tn/api/payments/konnect/webhook");
        // Silent, or Konnect shows the customer our one-word reply as a page.
        assertThat(body).contains("\"silentWebhook\":true");
    }

    @Test
    @DisplayName("With no public address it still sells, unlike Paynow")
    void noPublicAddressIsNotFatal() {
        PublicUrls none = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://example.com/api/payments/mpesa/callback", null));
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "konnectProduction", konnect.url());
        KonnectProvider stranded = new KonnectProvider(gateways, portalSettings, endpoints, none);
        initSucceeds();

        // Konnect can be asked how a payment ended, so the sweep settles it a
        // minute later. Refusing the sale the way Paynow has to would be wrong.
        assertThat(stranded.charge(request("10")).providerRef()).isEqualTo("abc123");
        assertThat(konnect.call("/payments/init-payment").body()).doesNotContain("webhook");
    }

    @Test
    @DisplayName("A refusal is surfaced with Konnect's own message")
    void refusalSurfaces() {
        konnect.on("POST /payments/init-payment", 401,
                "{\"errors\":[{\"message\":\"API key not found\"}]}");

        assertThatThrownBy(() -> provider.charge(request("10")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key not found");
    }

    @Test
    @DisplayName("Accepted with no pay URL is refused rather than left unopenable")
    void acceptedWithoutAPayUrlIsRefused() {
        konnect.on("POST /payments/init-payment", "{\"paymentRef\":\"abc123\"}");

        // A charge with nowhere to send the customer is not a charge.
        assertThatThrownBy(() -> provider.charge(request("10")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ----------------------------------------------------------------- outcome

    @Test
    @DisplayName("A payment still pending is not reported as unpaid")
    void pendingIsNotAFailure() {
        konnect.on("GET /payments/abc123",
                "{\"payment\":{\"status\":\"pending\",\"amount\":10000,\"orderId\":\"HS-88\"}}");

        // The customer is still on Konnect's page. Failing them cancels a live
        // sale, and this rail's callback cannot correct it afterwards.
        assertThat(provider.poll("abc123")).isEmpty();
    }

    @Test
    @DisplayName("Failed and expired both settle as unpaid, with the reason kept")
    void failedAndExpiredSettle() {
        for (String status : new String[]{"failed", "expired"}) {
            konnect.on("GET /payments/x-" + status,
                    "{\"payment\":{\"status\":\"" + status + "\",\"orderId\":\"HS-88\"}}");

            Optional<PaymentProvider.Settlement> settled = provider.poll("x-" + status);

            assertThat(settled).as(status).isPresent();
            assertThat(settled.get().paid()).isFalse();
            assertThat(settled.get().failureReason()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("An unsigned callback settles only what Konnect confirms")
    void callbackIsOnlyAHint() {
        // Konnect does not sign its callback. Believing a body that said
        // "completed" would be a free-internet generator for anyone who learned
        // a reference, so the body is read only for the reference.
        konnect.on("GET /payments/abc123",
                "{\"payment\":{\"status\":\"failed\",\"orderId\":\"HS-88\"}}");

        Optional<PaymentProvider.Settlement> settled = provider.settle(
                "{\"payment_ref\":\"abc123\",\"status\":\"completed\"}"
                        .getBytes(StandardCharsets.UTF_8), Map.of());

        assertThat(settled).isPresent();
        assertThat(settled.get().paid())
                .as("the body claimed completed; Konnect said failed")
                .isFalse();
    }

    @Test
    @DisplayName("A callback naming no payment is refused rather than guessed at")
    void callbackWithoutAReferenceIsRefused() {
        assertThatThrownBy(() -> provider.settle("{}".getBytes(StandardCharsets.UTF_8), Map.of()))
                .hasMessageContaining("no payment reference");
    }

    @Test
    @DisplayName("Either spelling of the reference is understood")
    void bothReferenceSpellings() {
        assertThat(KonnectProvider.referenceIn(
                "{\"payment_ref\":\"abc\"}".getBytes(StandardCharsets.UTF_8))).isEqualTo("abc");
        assertThat(KonnectProvider.referenceIn(
                "{\"paymentRef\":\"def\"}".getBytes(StandardCharsets.UTF_8))).isEqualTo("def");
        assertThat(KonnectProvider.referenceIn(new byte[0])).isNull();
    }

    // ------------------------------------------------------------------ market

    @Test
    @DisplayName("Outside Tunisia it is not offered")
    void outsideTunisiaItIsNotOffered() {
        country("KE", "KES");

        assertThat(provider.usable()).isFalse();
        assertThatThrownBy(() -> provider.charge(request("10")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(konnect.calls()).isEmpty();
    }

    @Test
    @DisplayName("Prices in the wrong currency stop it rather than mis-charging")
    void currencyMustAgree() {
        // Tunisia set, prices still in shillings. A plan priced 1000 would be
        // collected as 1000 dinars.
        country("TN", "KES");

        assertThat(provider.usable()).isFalse();
        assertThat(konnect.calls()).isEmpty();
    }

    @Test
    @DisplayName("Asking Konnect is what settles this rail")
    void pollable() {
        assertThat(provider.pollable()).isTrue();
    }
}
