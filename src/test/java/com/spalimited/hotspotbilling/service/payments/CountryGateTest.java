package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Rails that only exist in some countries are not offered in the others.
 *
 * <p>Tanzania is the case that exposed this. Paystack, Chapa and Paynow are each
 * licensed in a fixed list of countries; none of those lists contains Tanzania.
 * Until now nothing stopped a Tanzanian operator switching any of them on: the
 * key would save, the portal would offer "Card or bank", and every payment would
 * be refused by the provider for an unsupported currency. Nothing in the admin,
 * the portal or the logs would have said why.
 *
 * <p>The other half of this is as important and pulls the opposite way. The gate
 * must not touch settling or polling. A webhook carries news about money that has
 * already left a customer's account; dropping it because the country setting
 * changed since means the customer paid and got nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CountryGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    /** Every rail switched on and fully keyed, so only the country can refuse. */
    private void configured() {
        when(gateways.find(any())).thenAnswer(inv -> Optional.of(PaymentGateway.builder()
                .kind((PaymentGateway.Kind) inv.getArgument(0))
                .active(true)
                .consumerKey("12345")
                .secretKey("a-real-looking-key")
                .webhookSecret("a-real-looking-webhook-secret")
                .build()));
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    private PaystackProvider paystack() {
        return new PaystackProvider(gateways, portalSettings, JSON, null);
    }

    private ChapaProvider chapa() {
        return new ChapaProvider(gateways, portalSettings, JSON, null);
    }

    private PaynowProvider paynow() {
        return new PaynowProvider(gateways, portalSettings, null,
                new PublicUrls(new MpesaProperties(null, null, null, null, null,
                        "https://isp.example.net/api/payments/mpesa/callback", null)));
    }

    private static PaymentProvider.ChargeRequest request(String currency) {
        return new PaymentProvider.ChargeRequest(
                "255712345678", "buyer@example.com", new BigDecimal("2000"), currency,
                "HS-77", "1 hour of WiFi");
    }

    // ------------------------------------------------------------- Tanzania

    @Test
    @DisplayName("A Tanzanian operator is not offered Paystack, Chapa or Paynow")
    void tanzaniaIsNotOfferedRailsThatDoNotReachIt() {
        configured();
        country("TZ", "TZS");

        // Switched on, keyed, and still not usable -- because none of the three
        // can take a shilling from a Tanzanian customer.
        assertThat(paystack().usable()).isFalse();
        assertThat(chapa().usable()).isFalse();
        assertThat(paynow().usable()).isFalse();
    }

    @Test
    @DisplayName("Charging one anyway is refused before any money is asked for")
    void chargingOutsideTheMarketIsRefused() {
        configured();
        country("TZ", "TZS");

        // usable() keeps it off the portal; this is the second door, for
        // anything that reaches a provider directly.
        assertThatThrownBy(() -> paystack().charge(request("TZS")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> chapa().charge(request("TZS")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> paynow().charge(request("TZS")))
                .isInstanceOf(IllegalStateException.class);
    }

    // --------------------------------------------------- Inside the markets

    @Test
    @DisplayName("Each rail is still offered where it does operate")
    void insideTheMarketNothingChanges() {
        configured();

        country("KE", "KES");
        assertThat(paystack().usable()).as("Paystack in Kenya").isTrue();
        assertThat(chapa().usable()).as("Chapa in Kenya").isFalse();

        country("NG", "NGN");
        assertThat(paystack().usable()).as("Paystack in Nigeria").isTrue();

        country("ET", "ETB");
        assertThat(chapa().usable()).as("Chapa in Ethiopia").isTrue();
        assertThat(paystack().usable()).as("Paystack in Ethiopia").isFalse();

        country("ZW", "USD");
        assertThat(paynow().usable()).as("Paynow in Zimbabwe").isTrue();
        assertThat(chapa().usable()).as("Chapa in Zimbabwe").isFalse();
    }

    @Test
    @DisplayName("An unset country does not silently unlock a rail")
    void blankCountryDoesNotUnlockEverything() {
        configured();
        country(null, null);

        // Blank reads as Kenya, which is what the rest of the system already
        // assumes. What matters is that it is one country and not all of them.
        assertThat(chapa().usable()).isFalse();
        assertThat(paynow().usable()).isFalse();
    }

    // ------------------------------------------------ Money already in flight

    @Test
    @DisplayName("A webhook for money already taken is still honoured")
    void settlingIsNotGatedOnCountry() {
        configured();
        country("TZ", "TZS");

        // The country is wrong for Paystack, so nothing new can be charged --
        // but this webhook is about a payment that already happened. Refusing to
        // read it would take a customer's money and give them nothing. It must
        // get as far as checking the signature.
        assertThatThrownBy(() -> paystack().settle("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.Map.of("x-paystack-signature", "deadbeef")))
                .hasMessageContaining("signature");
    }
}
