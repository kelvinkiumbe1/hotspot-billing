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
 * MTN MoMo over a real socket.
 *
 * <p>MTN's request has more that can go silently wrong than any other rail here:
 * a UUID we generate and must echo in a header, a target environment that
 * differs per market, a subscription key on every call including the token
 * fetch, and Basic auth built from two fields that are easy to swap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MtnMomoHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    @Mock
    private PortalSettingsService portalSettings;

    private FakeGateway mtn;
    private MtnMomoProvider provider;

    @BeforeEach
    void setUp() {
        mtn = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "mtnSandbox", mtn.url());
        ReflectionTestUtils.setField(endpoints, "mtnProduction", mtn.url());

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.MTN_MOMO)
                .active(true)
                .environment(PaymentGateway.Environment.PRODUCTION)
                .secretKey("sub-key-123")       // Ocp-Apim-Subscription-Key
                .consumerKey("api-user-uuid")   // API user
                .consumerSecret("api-key-456")  // API key
                .build()));
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country("GH").build());

        provider = new MtnMomoProvider(gateways, endpoints, portalSettings);

        mtn.on("POST /collection/token/", """
                {"access_token":"tok-abc","token_type":"Bearer","expires_in":3600}""");
    }

    @AfterEach
    void tearDown() {
        mtn.close();
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "233241234567", null, new BigDecimal("12"), "GHS", "HS-9", "1 hour of WiFi");
    }

    @Test
    @DisplayName("A charge carries the subscription key, the target market and its own reference")
    void chargeSendsEverythingMtnNeeds() {
        mtn.on("POST /collection/v1_0/requesttopay", 202, "");

        PaymentProvider.Charge charge = provider.charge(request());

        FakeGateway.Call pay = mtn.call("/collection/v1_0/requesttopay");
        assertThat(pay.header("Ocp-Apim-Subscription-Key")).isEqualTo("sub-key-123");
        assertThat(pay.header("X-Target-Environment")).isEqualTo("mtnghana");
        assertThat(pay.header("Authorization")).isEqualTo("Bearer tok-abc");
        // The reference is ours and generated before the call, so a request that
        // times out having nonetheless reached MTN is still findable.
        assertThat(pay.header("X-Reference-Id")).isEqualTo(charge.providerRef());
        assertThat(charge.checkoutUrl()).as("MTN prompts the handset; there is no page").isNull();
        // Major units. MTN is not a minor-unit rail, so 12 must go as 12.
        assertThat(pay.body()).containsPattern("\"amount\":\"?12(\\.0+)?\"?[^0-9]");
    }

    @Test
    @DisplayName("The token call authenticates with the API user and key, not the subscription key")
    void tokenUsesBasicAuthFromTheRightPair() {
        mtn.on("POST /collection/v1_0/requesttopay", 202, "");

        provider.charge(request());

        FakeGateway.Call token = mtn.call("/collection/token/");
        String basic = token.header("Authorization");
        assertThat(basic).startsWith("Basic ");
        String decoded = new String(java.util.Base64.getDecoder()
                .decode(basic.substring("Basic ".length())));
        // Swapping these two is the classic MTN setup mistake, and MTN's error
        // for it says nothing useful.
        assertThat(decoded).isEqualTo("api-user-uuid:api-key-456");
        assertThat(token.header("Ocp-Apim-Subscription-Key")).isEqualTo("sub-key-123");
    }

    @Test
    @DisplayName("A successful status query reports paid, with MTN's own receipt")
    void pollReadsASuccessfulPayment() {
        mtn.on("GET /collection/v1_0/requesttopay/ref-1", """
                {"status":"SUCCESSFUL","amount":"12","currency":"GHS",
                 "externalId":"HS-9","financialTransactionId":"FT-77"}""");

        var settled = provider.poll("ref-1");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().receipt()).isEqualTo("FT-77");
        assertThat(settled.get().amount()).isEqualByComparingTo(new BigDecimal("12"));
    }

    @Test
    @DisplayName("PENDING is not an answer and must not read as a failure")
    void pendingIsNotAVerdict() {
        mtn.on("GET /collection/v1_0/requesttopay/ref-2", """
                {"status":"PENDING","amount":"12","currency":"GHS","externalId":"HS-9"}""");

        // Reporting this as "not paid" cancels a sale from a customer who is
        // still typing their PIN.
        assertThat(provider.poll("ref-2")).isEmpty();
    }

    @Test
    @DisplayName("MTN refusing the charge is reported rather than left pending")
    void refusalSurfaces() {
        mtn.on("POST /collection/v1_0/requesttopay", 400,
                "{\"message\":\"Currency not supported\"}");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A market with no target environment never reaches the network")
    void unservedMarketIsRefusedLocally() {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country("KE").build());

        // Kenya is M-Pesa's. Sending a live charge into a market the operator
        // has no agreement in is worse than refusing here.
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(mtn.calls()).as("nothing should have been sent").isEmpty();
    }
}
