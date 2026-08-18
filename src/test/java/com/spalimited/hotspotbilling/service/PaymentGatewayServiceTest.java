package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.repository.PaymentGatewayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Several ways to pay, all switched on at once.
 *
 * <p>Exactly one gateway could be active, and switching one on switched every
 * other one off. That is fine in Kenya, where M-Pesa is effectively the only
 * wallet, and wrong nearly everywhere else: a Tanzanian ISP has customers on
 * Vodacom, on Airtel and on Mixx, and being made to choose means choosing which
 * two thirds of the market cannot pay.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentGatewayServiceTest {

    @Mock
    private PaymentGatewayRepository gateways;

    @Mock
    private MpesaProperties props;

    @InjectMocks
    private PaymentGatewayService service;

    private final List<PaymentGateway> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(gateways.findAll()).thenReturn(stored);
        when(gateways.save(any())).thenAnswer(i -> i.getArgument(0));
        when(gateways.findByKind(any())).thenAnswer(i -> stored.stream()
                .filter(g -> g.getKind() == i.getArgument(0)).findFirst());
    }

    /** A configured, switched-on M-Pesa gateway. */
    private PaymentGateway mpesa(int order, boolean active) {
        PaymentGateway g = PaymentGateway.builder()
                .kind(PaymentGateway.Kind.MPESA_API).active(active).sortOrder(order)
                .consumerKey("k").consumerSecret("s").shortCode("174379").passkey("p")
                .build();
        stored.add(g);
        return g;
    }

    private PaymentGateway airtel(int order, boolean active) {
        PaymentGateway g = PaymentGateway.builder()
                .kind(PaymentGateway.Kind.AIRTEL_MONEY).active(active).sortOrder(order)
                .consumerKey("id").consumerSecret("secret").build();
        stored.add(g);
        return g;
    }

    private PaymentGateway paybill(int order, boolean active) {
        PaymentGateway g = PaymentGateway.builder()
                .kind(PaymentGateway.Kind.MPESA_PAYBILL_MANUAL).active(active).sortOrder(order)
                .paybillNumber("522522").build();
        stored.add(g);
        return g;
    }

    @Test
    @DisplayName("Two wallets can be on at once, which is the whole point")
    void severalAtOnce() {
        mpesa(10, true);
        airtel(20, true);

        assertThat(service.enabled()).hasSize(2);
        assertThat(service.enabled()).extracting(g -> g.getKind().name())
                .containsExactly("MPESA_API", "AIRTEL_MONEY");
    }

    @Test
    @DisplayName("Switching one on no longer switches the others off")
    void activateDoesNotStandOthersDown() {
        mpesa(10, true);
        airtel(100, false);

        service.activate(PaymentGateway.Kind.AIRTEL_MONEY);

        // The old behaviour here would have taken M-Pesa offline, and with it
        // every customer who uses it.
        assertThat(service.enabled()).hasSize(2);
    }

    @Test
    @DisplayName("A newly switched-on wallet goes to the end, not the front")
    void newlyEnabledGoesLast() {
        mpesa(10, true);
        PaymentGateway added = airtel(100, false);

        service.activate(PaymentGateway.Kind.AIRTEL_MONEY);

        // Jumping ahead would change what USSD uses and reorder the portal for
        // customers already used to seeing M-Pesa first.
        assertThat(added.getSortOrder()).isGreaterThan(10);
        assertThat(service.enabled().get(0).getKind()).isEqualTo(PaymentGateway.Kind.MPESA_API);
    }

    @Test
    @DisplayName("The default is the first offered, for the surfaces that cannot ask")
    void defaultIsTheFirst() {
        airtel(20, true);
        mpesa(10, true);

        // USSD and the WhatsApp bot have no way to show a picker.
        assertThat(service.active()).isPresent();
        assertThat(service.active().get().getKind()).isEqualTo(PaymentGateway.Kind.MPESA_API);
    }

    @Test
    @DisplayName("Switching off the last way to be paid is refused")
    void cannotDisableTheLastOne() {
        mpesa(10, true);

        // Allowed through, the admin looks healthy while every sale fails.
        assertThatThrownBy(() -> service.deactivate(PaymentGateway.Kind.MPESA_API))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only way customers can pay you");
    }

    @Test
    @DisplayName("Switching one off is fine when another remains")
    void canDisableWhenAnotherRemains() {
        PaymentGateway m = mpesa(10, true);
        airtel(20, true);

        service.deactivate(PaymentGateway.Kind.MPESA_API);

        assertThat(m.isActive()).isFalse();
        assertThat(service.enabled()).hasSize(1);
    }

    @Test
    @DisplayName("A switched-on but half-configured gateway is not offered")
    void unconfiguredIsNotOffered() {
        mpesa(10, true);
        // Active, but with no credentials — offering it would show a customer a
        // way to pay that cannot take their money.
        stored.add(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.STRIPE).active(true).sortOrder(20).build());

        assertThat(service.enabled()).hasSize(1);
    }

    @Test
    @DisplayName("Reordering changes both the list and the default")
    void reorderChangesTheDefault() {
        mpesa(10, true);
        airtel(20, true);

        service.reorder(List.of(PaymentGateway.Kind.AIRTEL_MONEY, PaymentGateway.Kind.MPESA_API));

        assertThat(service.active().get().getKind()).isEqualTo(PaymentGateway.Kind.AIRTEL_MONEY);
    }

    @Test
    @DisplayName("Manual instructions find the paybill even when a wallet is listed first")
    void manualInstructionsSkipAutomaticOnes() {
        mpesa(10, true);
        paybill(20, true);

        // Reading only the first enabled gateway would report "no payment
        // details" to a customer while a perfectly good paybill sat switched on.
        assertThat(service.manualInstructions()).containsEntry("paybillNumber", "522522");
    }

    @Test
    @DisplayName("Every hand-reconciled method is listed, not just the first")
    void allManualInstructions() {
        paybill(10, true);
        stored.add(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.BANK_TRANSFER).active(true).sortOrder(20)
                .bankName("Equity Bank").accountNumber("0123456789").build());

        // An operator may well have both, and showing one is the sort of thing a
        // customer reads as the business not knowing its own details.
        assertThat(service.allManualInstructions()).hasSize(2);
    }

    @Test
    @DisplayName("Every automatic rail can actually store its credentials")
    void everyAutomaticRailSaves() {
        // The branch that handled this listed kinds by name, so MTN MoMo,
        // Airtel, Chapa and Paynow all fell through to the manual branch, which
        // stores paybill numbers and silently discards API credentials. All four
        // were unconfigurable, and the settings screen looked like it had saved.
        for (PaymentGateway.Kind kind : new PaymentGateway.Kind[]{
                PaymentGateway.Kind.MTN_MOMO, PaymentGateway.Kind.AIRTEL_MONEY,
                PaymentGateway.Kind.CHAPA, PaymentGateway.Kind.PAYNOW,
                PaymentGateway.Kind.PAYSTACK}) {
            stored.clear();
            PaymentGateway saved = service.save(kind, PaymentGateway.builder()
                    .kind(kind)
                    .consumerKey("id").consumerSecret("secret")
                    .secretKey("sk").webhookSecret("whsec")
                    .environment(PaymentGateway.Environment.PRODUCTION)
                    .build(), "tester");

            assertThat(saved.isConfigured())
                    .as("%s could not be configured at all", kind)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Saving still keeps a stored secret when the field is left blank")
    void blankKeepsTheStoredSecret() {
        stored.add(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.AIRTEL_MONEY)
                .consumerKey("id").consumerSecret("original").build());

        // Editing the environment must not wipe the credentials and take the
        // gateway offline.
        PaymentGateway saved = service.save(PaymentGateway.Kind.AIRTEL_MONEY,
                PaymentGateway.builder().kind(PaymentGateway.Kind.AIRTEL_MONEY)
                        .environment(PaymentGateway.Environment.PRODUCTION).build(), "tester");

        assertThat(saved.getConsumerSecret()).isEqualTo("original");
        assertThat(saved.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("With nothing switched on there is no default and no pretending otherwise")
    void nothingEnabled() {
        mpesa(10, false);

        assertThat(service.enabled()).isEmpty();
        assertThat(service.active()).isEmpty();
    }
}
