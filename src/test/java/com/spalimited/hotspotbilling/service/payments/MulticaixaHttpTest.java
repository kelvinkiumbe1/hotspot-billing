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
 * Multicaixa Express over a real socket.
 *
 * <p>The refusal bodies here are the ones EMIS actually returned when probed —
 * the missing-token error, the invalid-token error, and the RESTEasy 500 that an
 * invented path gives. The success shapes could not be confirmed without a
 * merchant token, which is exactly why the readers are lenient about what counts
 * as unfinished and strict about what counts as paid.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MulticaixaHttpTest {

    private static final String MERCHANT_TOKEN = "a3f1c8d2-64b7-4e19-9c05-8d2f7b1e4a60";

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private FakeGateway emis;
    private MulticaixaProvider provider;

    @BeforeEach
    void setUp() {
        emis = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "multicaixa", emis.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.MULTICAIXA)
                .active(true)
                .secretKey(MERCHANT_TOKEN)
                .build()));
        country("AO", "AOA");

        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://isp.example.ao/api/payments/mpesa/callback", null));
        provider = new MulticaixaProvider(gateways, portalSettings, endpoints, urls);
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    @AfterEach
    void tearDown() {
        emis.close();
    }

    private void frameOpens() {
        emis.on("POST /v1/frameToken", "{\"id\":\"f47ac10b-58cc-4372-a567-0e02b2c3d479\"}");
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "244923456789", null, new BigDecimal("2500"), "AOA", "HS-64",
                "1 hour of WiFi");
    }

    // ------------------------------------------------------------------ charge

    @Test
    @DisplayName("The create call carries the fields EMIS accepted")
    void createBody() {
        frameOpens();

        provider.charge(request());

        // Every one of these was confirmed understood by the live API: a shaped
        // request with a fake merchant token got past body validation to
        // "invalid frame token", which it could not do with a field misnamed.
        String body = emis.call("/v1/frameToken").body();
        assertThat(body).contains("\"reference\":\"HS-64\"");
        assertThat(body).contains("\"amount\":\"2500.00\"");
        assertThat(body).contains("\"token\":\"" + MERCHANT_TOKEN + "\"");
        assertThat(body).contains("\"mobile\":\"PAYMENT\"");
        assertThat(body).contains("\"qrCode\":\"PAYMENT\"");
        // Cards off on purpose: enabling them needs a separate agreement with
        // EMIS, and a method offered without one fails at the last step.
        assertThat(body).contains("\"card\":\"DISABLED\"");
    }

    @Test
    @DisplayName("The customer is sent to EMIS's frame, at the path that answers")
    void checkoutUrlIsTheFrame() {
        frameOpens();

        PaymentProvider.Charge charge = provider.charge(request());

        // /webframe/?token=... answers 200; /webframe/frame?token=... is a 404 and
        // was the wrong guess. Worth pinning, because the wrong one is a customer
        // looking at an error page having pressed Pay.
        assertThat(charge.checkoutUrl())
                .isEqualTo(emis.url() + "/?token=f47ac10b-58cc-4372-a567-0e02b2c3d479");
        assertThat(charge.providerRef()).isEqualTo("f47ac10b-58cc-4372-a567-0e02b2c3d479");
    }

    @Test
    @DisplayName("EMIS is told where to call back")
    void callbackUrlPointsAtUs() {
        frameOpens();

        provider.charge(request());

        assertThat(emis.call("/v1/frameToken").body())
                .contains("https://isp.example.ao/api/payments/multicaixa/webhook");
    }

    @Test
    @DisplayName("With no public address it still sells, because EMIS can be asked")
    void noPublicAddressIsNotFatal() {
        PublicUrls none = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://example.com/api/payments/mpesa/callback", null));
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "multicaixa", emis.url());
        MulticaixaProvider stranded = new MulticaixaProvider(
                gateways, portalSettings, endpoints, none);
        frameOpens();

        assertThat(stranded.charge(request()).providerRef()).isNotBlank();
        assertThat(emis.call("/v1/frameToken").body()).doesNotContain("callbackUrl");
    }

    @Test
    @DisplayName("A missing merchant token is reported in EMIS's own words")
    void missingTokenSurfaces() {
        // Verbatim from the live API, for an empty body.
        emis.on("POST /v1/frameToken", 400,
                "{\"code\":\"BODY\",\"message\":\"Merchant Token is required to create a new "
                + "Frame Token.\"}");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Merchant Token is required");
    }

    @Test
    @DisplayName("An invalid merchant token is reported in EMIS's own words")
    void invalidTokenSurfaces() {
        // Verbatim from the live API, for a shaped request with a fake token.
        emis.on("POST /v1/frameToken", 400,
                "{\"code\":\"104\",\"message\":\"invalid frame token\"}");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid frame token");
    }

    @Test
    @DisplayName("A frame id under any of its plausible names is found")
    void frameIdIsFoundHowEverItIsNamed() {
        // The one field whose name could not be confirmed without a merchant
        // token. Getting it wrong would refuse every payment EMIS had accepted,
        // so this looks in more than one place.
        assertThat(read("{\"id\":\"A\"}")).isEqualTo("A");
        assertThat(read("{\"frameToken\":\"B\"}")).isEqualTo("B");
        assertThat(read("{\"token\":\"C\"}")).isEqualTo("C");
        assertThat(read("{\"code\":\"104\",\"message\":\"invalid frame token\"}")).isNull();
        assertThat(read("{}")).isNull();
    }

    private static String read(String json) {
        return MulticaixaProvider.frameId(
                new tools.jackson.databind.ObjectMapper().readTree(json));
    }

    // ----------------------------------------------------------------- outcome

    @Test
    @DisplayName("An accepted payment settles")
    void acceptedSettles() {
        emis.on("GET /v1/frameToken/frame-1",
                "{\"status\":\"ACCEPTED\",\"reference\":\"HS-64\",\"amount\":\"2500.00\","
                + "\"transactionId\":\"MCX-778\"}");

        Optional<PaymentProvider.Settlement> settled = provider.poll("frame-1");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().reference()).isEqualTo("HS-64");
        assertThat(settled.get().receipt()).isEqualTo("MCX-778");
        assertThat(settled.get().currency()).isEqualTo("AOA");
    }

    @Test
    @DisplayName("A rejected payment settles as unpaid, with the reason kept")
    void rejectedSettles() {
        emis.on("GET /v1/frameToken/frame-2",
                "{\"status\":\"REJECTED\",\"reference\":\"HS-64\"}");

        Optional<PaymentProvider.Settlement> settled = provider.poll("frame-2");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isFalse();
        assertThat(settled.get().failureReason()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("Anything unfinished, or unrecognised, is not a failure")
    void unfinishedAndUnknownBothWait() {
        // The status vocabulary is the part that could not be confirmed. So the
        // reader is strict about what counts as paid and lenient about the rest:
        // the worst an unknown word can do is delay a voucher, never deny one.
        for (String status : new String[]{"CREATED", "PENDING", "PROCESSING",
                "SOMETHING_EMIS_ADDED_LATER"}) {
            emis.on("GET /v1/frameToken/w-" + status,
                    "{\"status\":\"" + status + "\",\"reference\":\"HS-64\"}");

            assertThat(provider.poll("w-" + status)).as(status).isEmpty();
        }
    }

    @Test
    @DisplayName("A gateway error is not read as news about the payment")
    void gatewayErrorIsNotAVerdict() {
        // What a bogus frame id actually returns from the live API. It says
        // nothing about any payment, so it must not settle one.
        emis.on("GET /v1/frameToken/nope", 400,
                "{\"code\":\"201\",\"message\":\"internal error\"}");

        assertThat(provider.poll("nope")).isEmpty();
    }

    @Test
    @DisplayName("An unsigned callback settles only what EMIS confirms")
    void callbackIsOnlyAHint() {
        emis.on("GET /v1/frameToken/frame-3",
                "{\"status\":\"REJECTED\",\"reference\":\"HS-64\"}");

        Optional<PaymentProvider.Settlement> settled = provider.settle(
                "{\"id\":\"frame-3\",\"status\":\"ACCEPTED\"}".getBytes(StandardCharsets.UTF_8),
                Map.of());

        // EMIS does not sign this. Believing a body that said ACCEPTED would be a
        // free-internet generator for anyone who learned a frame id.
        assertThat(settled).isPresent();
        assertThat(settled.get().paid())
                .as("the body claimed ACCEPTED; EMIS said REJECTED")
                .isFalse();
    }

    @Test
    @DisplayName("A callback naming no frame is refused rather than guessed at")
    void callbackWithoutAFrameIsRefused() {
        assertThatThrownBy(() -> provider.settle("{}".getBytes(StandardCharsets.UTF_8), Map.of()))
                .hasMessageContaining("no frame id");
    }

    // ------------------------------------------------------------------ market

    @Test
    @DisplayName("Outside Angola it is not offered")
    void outsideAngolaItIsNotOffered() {
        country("KE", "KES");

        assertThat(provider.usable()).isFalse();
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(emis.calls()).isEmpty();
    }

    @Test
    @DisplayName("Prices have to be in kwanzas")
    void currencyMustAgree() {
        country("AO", "KES");

        assertThat(provider.usable()).isFalse();
        assertThat(emis.calls()).isEmpty();
    }

    @Test
    @DisplayName("Asking EMIS is what settles this rail")
    void pollable() {
        assertThat(provider.pollable()).isTrue();
    }
}
