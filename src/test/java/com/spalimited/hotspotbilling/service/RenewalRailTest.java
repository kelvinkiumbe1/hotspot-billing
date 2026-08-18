package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.service.payments.MandateService;
import com.spalimited.hotspotbilling.service.payments.PaymentProvider;
import com.spalimited.hotspotbilling.service.payments.PaymentProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A PPPoE renewal used to be Daraja or nothing.
 *
 * <p>{@code initiateStk} called MpesaService directly rather than going through
 * the provider abstraction, so a Ghanaian ISP could configure MTN MoMo
 * perfectly and still have no way to bill a monthly customer. That is the layer
 * underneath recurring: there is no point storing an authorisation for a rail
 * that cannot charge a subscriber in the first place.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RenewalRailTest {

    @Mock private SubscriberRepository subscribers;
    @Mock private MandateService mandates;
    @Mock private com.spalimited.hotspotbilling.repository.RouterRepository routers;
    @Mock private SubscriptionPaymentRepository payments;
    @Mock private MikrotikService mikrotikService;
    @Mock private MpesaService mpesaService;
    @Mock private NotificationService notificationService;
    @Mock private PortalSettingsService portalSettingsService;
    @Mock private InvoiceService invoiceService;
    @Mock private EtimsService etimsService;
    @Mock private ReferralService referralService;
    @Mock private PaymentProviders providers;
    @Mock private MoneyService money;
    @Mock private PaymentProvider paystack;

    @InjectMocks
    private SubscriptionService service;

    private Subscriber sub;
    private final List<SubscriptionPayment> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sub = Subscriber.builder()
                .id(9L).pppoeUsername("kofi").phoneNumber("233241234567")
                .monthlyFee(new BigDecimal("120"))
                // NOT NULL in the schema and always set on create; a null here
                // is a fixture that could not exist rather than a case to cover.
                .paidUntil(Instant.now().plusSeconds(86400))
                .status(Subscriber.Status.ACTIVE)
                .build();
        when(subscribers.findById(9L)).thenReturn(Optional.of(sub));
        when(portalSettingsService.settings()).thenReturn(
                com.spalimited.hotspotbilling.domain.PortalSettings.builder()
                        .businessName("Test ISP").build());
        when(money.code()).thenReturn("GHS");
        when(paystack.kind()).thenReturn(PaymentGateway.Kind.PAYSTACK);
        when(paystack.supportsRecurring()).thenReturn(true);
        saved.clear();
        when(payments.save(any())).thenAnswer(i -> {
            SubscriptionPayment p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(100L + saved.size());
                saved.add(p);
            }
            return p;
        });
    }

    @Test
    @DisplayName("A renewal goes down the configured rail, not down Daraja")
    void renewalUsesTheConfiguredRail() {
        when(providers.active()).thenReturn(Optional.of(paystack));
        when(paystack.charge(any())).thenReturn(
                new PaymentProvider.Charge("ps-ref-1", "https://checkout.paystack.com/x"));

        SubscriptionService.Started started = service.initiateRenewal(9L, 1, null);

        verify(mpesaService, never()).stkPush(any(), any(), any());
        assertThat(started.checkoutUrl()).isEqualTo("https://checkout.paystack.com/x");
        assertThat(started.payment().getProvider()).isEqualTo("PAYSTACK");
        assertThat(started.payment().getMethod()).isEqualTo(SubscriptionPayment.Method.ONLINE);
        assertThat(started.payment().getCheckoutRequestId()).isEqualTo("ps-ref-1");
    }

    @Test
    @DisplayName("With nothing else configured it still falls back to Daraja")
    void fallsBackToDaraja() {
        // An existing Kenyan deployment must behave exactly as it did.
        when(providers.active()).thenReturn(Optional.empty());
        when(mpesaService.stkPush(any(), any(), any())).thenReturn("ws_CO_1");

        SubscriptionService.Started started = service.initiateRenewal(9L, 1, null);

        assertThat(started.payment().getMethod()).isEqualTo(SubscriptionPayment.Method.MPESA);
        assertThat(started.payment().getCheckoutRequestId()).isEqualTo("ws_CO_1");
        assertThat(started.checkoutUrl()).isNull();
    }

    @Test
    @DisplayName("A rail that refuses leaves a FAILED row, not a pending one")
    void refusalIsRecorded() {
        when(providers.active()).thenReturn(Optional.of(paystack));
        when(paystack.charge(any())).thenThrow(new IllegalStateException("no"));

        try {
            service.initiateRenewal(9L, 1, null);
        } catch (RuntimeException expected) {
            // The point is the row, not the throw.
        }
        // A PENDING row nobody will ever settle is what the reconciliation
        // sweep would later time out and report as a lost payment.
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStatus()).isEqualTo(SubscriptionPayment.Status.FAILED);
    }

    @Test
    @DisplayName("A settled renewal extends the subscription once, however many times it arrives")
    void settlesOnceOnly() {
        SubscriptionPayment payment = SubscriptionPayment.builder()
                .id(55L).subscriber(sub).amount(new BigDecimal("120")).months(1)
                .method(SubscriptionPayment.Method.ONLINE).provider("PAYSTACK")
                .status(SubscriptionPayment.Status.PENDING)
                .checkoutRequestId("ps-ref-1")
                .build();
        when(payments.findByCheckoutRequestId("ps-ref-1")).thenReturn(Optional.of(payment));
        when(payments.findByCheckoutRequestId("")).thenReturn(Optional.empty());

        assertThat(service.handleProviderSettlement("ps-ref-1", null, true, "R1", null)).isTrue();
        assertThat(payment.getStatus()).isEqualTo(SubscriptionPayment.Status.SUCCESS);

        Instant firstCompleted = payment.getCompletedAt();
        // Every rail retries until it gets a 2xx, and a mandate charge marks
        // itself paid synchronously — so repeats are the normal case.
        assertThat(service.handleProviderSettlement("ps-ref-1", null, true, "R1", null)).isTrue();
        assertThat(payment.getCompletedAt()).isEqualTo(firstCompleted);
    }

    @Test
    @DisplayName("A settlement for somebody else's reference is handed back, not swallowed")
    void unknownReferenceIsNotOurs() {
        when(payments.findByCheckoutRequestId(any())).thenReturn(Optional.empty());

        // PaymentService needs a false here to log it as unknown rather than
        // silently believing a renewal was handled.
        assertThat(service.handleProviderSettlement("nope", "nope", true, null, null)).isFalse();
    }
}
