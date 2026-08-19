package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import com.spalimited.hotspotbilling.domain.PaymentMandate;
import com.spalimited.hotspotbilling.service.MpesaService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Daraja over a real socket.
 *
 * <p>The one rail that has ever moved real money, so its STK path is proven by
 * customers rather than by tests. Its standing order is not: M-Pesa Ratiba has
 * never been set up for anybody, and it is the recurring mechanism for the only
 * market this system actually sells in.
 *
 * <p>Needs no production change to intercept — DarajaConfig has always carried
 * its own base URL, because Safaricom picks sandbox against production by host.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DarajaHttpTest {

    @Mock
    private PaymentGatewayService gatewayService;

    private FakeGateway daraja;
    private MpesaService mpesa;

    @BeforeEach
    void setUp() {
        daraja = new FakeGateway();

        when(gatewayService.daraja()).thenReturn(new PaymentGatewayService.DarajaConfig(
                daraja.url(), "consumer-key", "consumer-secret",
                "174379", "passkey-abc", true, "apiuser", "sec-cred"));

        MpesaProperties props = new MpesaProperties(
                daraja.url(), null, null, null, null,
                "https://isp.example.net/api/payments/mpesa/callback", "522522");
        mpesa = new MpesaService(props, gatewayService);

        daraja.on("GET /oauth/v1/generate", """
                {"access_token":"daraja-token","expires_in":"3599"}""");
    }

    @AfterEach
    void tearDown() {
        daraja.close();
    }

    @Test
    @DisplayName("An STK push carries the shortcode, the callback and a whole-shilling amount")
    void stkPushShape() {
        daraja.on("POST /mpesa/stkpush/v1/processrequest", """
                {"ResponseCode":"0","CheckoutRequestID":"ws_CO_123","MerchantRequestID":"m-1"}""");

        String id = mpesa.stkPush("254712345678", new BigDecimal("50"), "HS-1");

        String body = daraja.call("/mpesa/stkpush/v1/processrequest").body();
        assertThat(body).contains("\"BusinessShortCode\":\"174379\"");
        assertThat(body).contains("\"PhoneNumber\":\"254712345678\"");
        // Daraja rejects a decimal amount outright.
        assertThat(body).containsPattern("\"Amount\":50[^0-9.]");
        assertThat(body).contains("https://isp.example.net/api/payments/mpesa/callback");
        assertThat(body).contains("\"AccountReference\":\"HS-1\"");
        assertThat(id).isEqualTo("ws_CO_123");
    }

    @Test
    @DisplayName("The password is the shortcode, passkey and timestamp, base64-encoded")
    void passwordIsBuiltCorrectly() {
        daraja.on("POST /mpesa/stkpush/v1/processrequest", """
                {"ResponseCode":"0","CheckoutRequestID":"ws_CO_1"}""");

        mpesa.stkPush("254712345678", new BigDecimal("50"), "HS-1");

        String body = daraja.call("/mpesa/stkpush/v1/processrequest").body();
        String password = body.replaceAll(".*\"Password\":\"([^\"]+)\".*", "$1");
        String timestamp = body.replaceAll(".*\"Timestamp\":\"([^\"]+)\".*", "$1");
        // Safaricom rejects a wrong password with a generic error, so getting
        // the concatenation order wrong is invisible until nothing ever works.
        assertThat(new String(Base64.getDecoder().decode(password)))
                .isEqualTo("174379" + "passkey-abc" + timestamp);
    }

    @Test
    @DisplayName("Daraja rejecting the push is reported rather than returning a null id")
    void rejectionSurfaces() {
        daraja.on("POST /mpesa/stkpush/v1/processrequest", """
                {"ResponseCode":"1","errorMessage":"Invalid Access Token"}""");

        // A null CheckoutRequestID would be stored against the payment and the
        // callback could never be matched to it.
        assertThatThrownBy(() -> mpesa.stkPush("254712345678", new BigDecimal("50"), "HS-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("An unconfigured Daraja never reaches the network")
    void unconfiguredRefusesLocally() {
        when(gatewayService.daraja()).thenReturn(new PaymentGatewayService.DarajaConfig(
                daraja.url(), null, null, null, null, false, null, null));

        assertThatThrownBy(() -> mpesa.stkPush("254712345678", new BigDecimal("50"), "HS-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set up");
        assertThat(daraja.calls()).isEmpty();
    }

    @Test
    @DisplayName("A standing order carries the dates, frequency and amount Ratiba wants")
    void standingOrderShape() {
        daraja.on("POST /standingorder/v1/createStandingOrderExternal", """
                {"ResponseHeader":{"responseCode":"200","responseDescription":"Request accepted"}}""");

        PaymentMandate mandate = PaymentMandate.builder()
                .subscriberId(4L)
                .amount(new BigDecimal("1500"))
                .frequency(PaymentMandate.Frequency.MONTHLY)
                .startsOn(LocalDate.of(2026, 9, 1))
                .externalRef("SO-4-20260901")
                .build();

        mpesa.createStandingOrder(mandate, "254712345678");

        String body = daraja.call("/standingorder/v1/createStandingOrderExternal").body();
        // Ratiba wants yyyyMMdd, not an ISO date -- an ISO one is rejected with
        // a validation error that does not name the field.
        assertThat(body).contains("20260901");
        assertThat(body).doesNotContain("2026-09-01");
        assertThat(body).contains("254712345678");
        assertThat(body).contains("1500");
        // Monthly is frequency code 4 in Safaricom's table.
        assertThat(body).contains("\"Frequency\":\"4\"");
    }

    @Test
    @DisplayName("Ratiba refusing the standing order is raised, not silently ignored")
    void standingOrderRefusalSurfaces() {
        daraja.on("POST /standingorder/v1/createStandingOrderExternal", """
                {"ResponseHeader":{"responseCode":"400","responseDescription":"Invalid request"}}""");

        PaymentMandate mandate = PaymentMandate.builder()
                .subscriberId(4L).amount(new BigDecimal("1500"))
                .frequency(PaymentMandate.Frequency.MONTHLY)
                .startsOn(LocalDate.of(2026, 9, 1))
                .externalRef("SO-4").build();

        // A mandate left PENDING on a request Safaricom refused would stop the
        // customer being chased while nothing collects.
        assertThatThrownBy(() -> mpesa.createStandingOrder(mandate, "254712345678"))
                .isInstanceOf(RuntimeException.class);
    }
}
