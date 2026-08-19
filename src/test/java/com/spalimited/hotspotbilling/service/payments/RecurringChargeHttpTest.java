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
 * Charging a customer who is not there.
 *
 * <p>The highest-consequence path in the whole payments package and, until now,
 * the least exercised. Nobody is watching a renewal go through: it either takes
 * the right money quietly or it does something wrong quietly. A decline
 * mistaken for a success gives a month of internet away; a success mistaken for
 * a decline chases a customer who has already paid.
 *
 * <p>Stripe's off-session path is covered in StripeHttpTest, which also holds
 * the confirm / off_session / allow_redirects flags. These are the two that
 * charge a stored token.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecurringChargeHttpTest {

    @Mock
    private PaymentGatewayService gateways;

    private FakeGateway remote;

    @AfterEach
    void tearDown() {
        if (remote != null) {
            remote.close();
        }
    }

    private static PaymentProvider.ChargeRequest renewal(String currency, String amount) {
        return new PaymentProvider.ChargeRequest(
                "254712345678", null, new BigDecimal(amount), currency, "PPPOE-4-88",
                "Internet renewal");
    }

    private PaystackProvider paystack() {
        remote = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "paystack", remote.url());
        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.PAYSTACK).active(true)
                .secretKey("sk_live_x").build()));
        return new PaystackProvider(gateways, new ObjectMapper(), endpoints);
    }

    private FlutterwaveProvider flutterwave() {
        remote = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "flutterwave", remote.url());
        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.FLUTTERWAVE).active(true)
                .secretKey("FLWSECK-x").webhookSecret("h").build()));
        return new FlutterwaveProvider(gateways, new ObjectMapper(), endpoints);
    }

    // ---------------------------------------------------------------- Paystack

    @Test
    @DisplayName("Paystack charges the stored authorization, in minor units")
    void paystackChargesTheAuthorization() {
        PaystackProvider provider = paystack();
        remote.on("POST /transaction/charge_authorization", """
                {"status":true,"data":{"status":"success","reference":"PPPOE-4-88"}}""");

        PaymentProvider.Charge charge = provider.chargeStored("AUTH_saved", renewal("KES", "1500"));

        String body = remote.call("/transaction/charge_authorization").body();
        assertThat(body).contains("\"authorization_code\":\"AUTH_saved\"");
        assertThat(body).contains("\"amount\":150000");
        assertThat(body).contains("\"reference\":\"PPPOE-4-88\"");
        // No page: there is nobody to send anywhere.
        assertThat(charge.checkoutUrl()).isNull();
    }

    @Test
    @DisplayName("Paystack reporting a declined charge is not treated as a renewal")
    void paystackDeclineIsNotSuccess() {
        PaystackProvider provider = paystack();
        // The envelope says status:true -- Paystack is reporting successfully
        // that the charge failed. Reading the envelope gives a customer a free
        // month.
        remote.on("POST /transaction/charge_authorization", """
                {"status":true,"data":{"status":"failed",
                 "gateway_response":"Insufficient funds"}}""");

        assertThatThrownBy(() -> provider.chargeStored("AUTH_saved", renewal("KES", "1500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    @DisplayName("Paystack refusing at the envelope level is also a decline")
    void paystackEnvelopeFailure() {
        PaystackProvider provider = paystack();
        remote.on("POST /transaction/charge_authorization", """
                {"status":false,"message":"Authorization code is invalid"}""");

        // An expired or revoked authorisation lands here, and the mandate has to
        // be stood down rather than retried forever.
        assertThatThrownBy(() -> provider.chargeStored("AUTH_dead", renewal("KES", "1500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authorization code is invalid");
    }

    @Test
    @DisplayName("A zero-decimal renewal is not multiplied by a hundred either")
    void paystackZeroDecimalOnRenewal() {
        PaystackProvider provider = paystack();
        remote.on("POST /transaction/charge_authorization", """
                {"status":true,"data":{"status":"success","reference":"PPPOE-4-88"}}""");

        // The same list that was short for the interactive charge is used here.
        // Unattended, a hundredfold overcharge would repeat every month.
        provider.chargeStored("AUTH_saved", renewal("XOF", "5000"));

        assertThat(remote.call("/transaction/charge_authorization").body())
                .containsPattern("\"amount\":5000[^0-9]");
    }

    // ------------------------------------------------------------- Flutterwave

    @Test
    @DisplayName("Flutterwave charges the saved card in major units")
    void flutterwaveChargesTheToken() {
        FlutterwaveProvider provider = flutterwave();
        remote.on("POST /tokenized-charges", """
                {"status":"success","data":{"status":"successful","flw_ref":"FLW-9"}}""");

        PaymentProvider.Charge charge = provider.chargeStored("flw-tok", renewal("KES", "1500"));

        String body = remote.call("/tokenized-charges").body();
        assertThat(body).contains("\"token\":\"flw-tok\"");
        // Major units. Minor here takes a hundred times the monthly fee from a
        // customer who is not watching.
        assertThat(body).containsPattern("\"amount\":\"?1500(\\.0+)?\"?[^0-9]");
        assertThat(body).contains("\"tx_ref\":\"PPPOE-4-88\"");
        assertThat(charge.providerRef()).isEqualTo("FLW-9");
        assertThat(charge.checkoutUrl()).isNull();
    }

    @Test
    @DisplayName("Flutterwave's inner status decides, not the envelope")
    void flutterwaveDeclineIsNotSuccess() {
        FlutterwaveProvider provider = flutterwave();
        remote.on("POST /tokenized-charges", """
                {"status":"success","data":{"status":"failed",
                 "processor_response":"Do not honour"}}""");

        assertThatThrownBy(() -> provider.chargeStored("flw-tok", renewal("KES", "1500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Do not honour");
    }

    @Test
    @DisplayName("A dead token is reported rather than retried silently")
    void flutterwaveEnvelopeFailure() {
        FlutterwaveProvider provider = flutterwave();
        remote.on("POST /tokenized-charges", """
                {"status":"error","message":"Token not found"}""");

        assertThatThrownBy(() -> provider.chargeStored("flw-gone", renewal("KES", "1500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Token not found");
    }

    @Test
    @DisplayName("A rail that cannot charge again says so instead of trying")
    void railsWithoutRecurringRefuse() {
        // Claiming the ability and failing at renewal is worse than not claiming
        // it: the operator stops chasing on the strength of it.
        remote = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "chapa", remote.url());
        ChapaProvider chapa = new ChapaProvider(gateways, new ObjectMapper(), endpoints);

        assertThat(chapa.supportsRecurring()).isFalse();
        assertThatThrownBy(() -> chapa.chargeStored("anything", renewal("ETB", "500")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(remote.calls()).as("nothing should have been sent").isEmpty();
    }
}
