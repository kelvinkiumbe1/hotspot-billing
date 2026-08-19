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
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Orange Money over a real socket.
 *
 * <p>Three things here are only checkable by making the request: the token call
 * is HTTP Basic with a form body where every other rail sends JSON, the country
 * segment sits inside the path, and the status query needs the pay token, order
 * id and amount together — which is why the provider reference is three values
 * glued into one string.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrangeMoneyHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    @Mock
    private PortalSettingsService portalSettings;

    private FakeGateway orange;
    private OrangeMoneyProvider provider;

    @BeforeEach
    void setUp() {
        orange = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "orange", orange.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.ORANGE_MONEY)
                .active(true)
                .environment(PaymentGateway.Environment.PRODUCTION)
                .consumerKey("orange-client")
                .consumerSecret("orange-secret")
                .shortCode("MERCHANT-KEY-1")
                .build()));
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country("SN").currencyCode("XOF").build());

        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null, "https://isp.example.net/api/payments/mpesa/callback", null));

        provider = new OrangeMoneyProvider(gateways, portalSettings, urls, endpoints);

        orange.on("POST /oauth/v3/token", """
                {"access_token":"tok-orange","token_type":"Bearer","expires_in":3600}""");
    }

    @AfterEach
    void tearDown() {
        orange.close();
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "221771234567", null, new BigDecimal("500"), "XOF", "HS-11", "1 hour of WiFi");
    }

    @Test
    @DisplayName("The token call is Basic auth with a form body, not JSON")
    void tokenIsBasicAndFormEncoded() {
        orange.on("POST /orange-money-webpay/sn/v1/webpayment", """
                {"pay_token":"PT-1","payment_url":"https://webpayment.orange.sn/x","notif_token":"NT-1"}""");

        provider.charge(request());

        FakeGateway.Call token = orange.call("/oauth/v3/token");
        assertThat(token.header("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(token.body()).contains("grant_type=client_credentials");
        String basic = token.header("Authorization");
        assertThat(basic).startsWith("Basic ");
        assertThat(new String(Base64.getDecoder().decode(basic.substring(6))))
                .isEqualTo("orange-client:orange-secret");
    }

    @Test
    @DisplayName("The country sits in the path, and the merchant key in the body")
    void marketSegmentAndMerchantKey() {
        orange.on("POST /orange-money-webpay/sn/v1/webpayment", """
                {"pay_token":"PT-1","payment_url":"https://webpayment.orange.sn/x"}""");

        PaymentProvider.Charge charge = provider.charge(request());

        // Senegal is "sn" in the path. A wrong segment is a 404 from Orange
        // with nothing explaining why.
        FakeGateway.Call call = orange.call("/orange-money-webpay/sn/v1/webpayment");
        assertThat(call.body()).contains("\"merchant_key\":\"MERCHANT-KEY-1\"");
        assertThat(call.body()).contains("\"order_id\":\"HS-11\"");
        // XOF has no minor unit, so 500 must go as 500.
        assertThat(call.body()).containsPattern("\"amount\":500[^0-9]");
        assertThat(charge.checkoutUrl()).isEqualTo("https://webpayment.orange.sn/x");
    }

    @Test
    @DisplayName("Orange is told where to send the customer back and where to notify")
    void callbackUrlsPointAtUs() {
        orange.on("POST /orange-money-webpay/sn/v1/webpayment", """
                {"pay_token":"PT-1","payment_url":"https://x"}""");

        provider.charge(request());

        String body = orange.call("/orange-money-webpay/sn/v1/webpayment").body();
        assertThat(body).contains("https://isp.example.net/api/payments/orange-money/webhook");
        assertThat(body).contains("https://isp.example.net/?paid=");
    }

    @Test
    @DisplayName("The reference carries the three values the status query needs")
    void referenceCarriesEverything() {
        orange.on("POST /orange-money-webpay/sn/v1/webpayment", """
                {"pay_token":"PT-9","payment_url":"https://x"}""");

        PaymentProvider.Charge charge = provider.charge(request());

        // Orange cannot be asked about a payment with the token alone.
        OrangeMoneyProvider.Ref ref = OrangeMoneyProvider.decodeRef(charge.providerRef());
        assertThat(ref).isNotNull();
        assertThat(ref.payToken()).isEqualTo("PT-9");
        assertThat(ref.orderId()).isEqualTo("HS-11");
        assertThat(ref.amount()).isEqualTo(500);
    }

    @Test
    @DisplayName("A status query sends all three, and reads the verdict")
    void statusQuerySendsAllThree() {
        orange.on("POST /orange-money-webpay/sn/v1/transactionstatus", """
                {"status":"SUCCESS","txnid":"OM-42"}""");

        var settled = provider.poll(OrangeMoneyProvider.encodeRef("PT-9", "HS-11", 500));

        String body = orange.call("/orange-money-webpay/sn/v1/transactionstatus").body();
        assertThat(body).contains("\"pay_token\":\"PT-9\"");
        assertThat(body).contains("\"order_id\":\"HS-11\"");
        assertThat(body).containsPattern("\"amount\":500[^0-9]");
        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().receipt()).isEqualTo("OM-42");
    }

    @Test
    @DisplayName("The sandbox substitutes its fake currency rather than being rejected")
    void sandboxUsesOrangesTestCurrency() {
        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.ORANGE_MONEY).active(true)
                .environment(PaymentGateway.Environment.SANDBOX)
                .consumerKey("c").consumerSecret("s").shortCode("MK").build()));
        orange.on("POST /orange-money-webpay/dev/v1/webpayment", """
                {"pay_token":"PT-1","payment_url":"https://x"}""");

        provider.charge(request());

        // Orange's sandbox only accepts 1 "OUV". Sending the real figure is
        // rejected with an error an operator reads as broken credentials.
        String body = orange.call("/orange-money-webpay/dev/v1/webpayment").body();
        assertThat(body).contains("\"currency\":\"OUV\"");
        assertThat(body).containsPattern("\"amount\":1[^0-9]");
    }

    @Test
    @DisplayName("Orange refusing the payment is surfaced, not left pending")
    void refusalSurfaces() {
        orange.on("POST /orange-money-webpay/sn/v1/webpayment", 400, """
                {"message":"Merchant key is invalid"}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PENDING from the status query is not a verdict")
    void pendingIsNotAVerdict() {
        orange.on("POST /orange-money-webpay/sn/v1/transactionstatus", """
                {"status":"PENDING"}""");

        assertThat(provider.poll(OrangeMoneyProvider.encodeRef("PT", "HS-11", 500))).isEmpty();
    }
}
