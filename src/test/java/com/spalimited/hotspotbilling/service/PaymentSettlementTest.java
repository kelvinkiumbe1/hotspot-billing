package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What a provider's word about an amount is worth.
 *
 * <p>This exists because of a bug that charged customers and gave them nothing.
 * The amount check treated "reported a different amount" and "reported no
 * amount" as the same thing. Airtel's enquiry has no amount field at all, so
 * every successful Airtel payment that arrived by webhook was marked FAILED —
 * and being no longer PENDING, reconciliation would never look at it again.
 *
 * <p>The check itself is worth keeping and is tested here too: for the rails
 * that settle from a webhook body rather than from an answer we went and asked
 * for, the body is the part to trust least.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentSettlementTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private com.spalimited.hotspotbilling.repository.PlanRepository planRepository;
    @Mock private MpesaService mpesaService;
    @Mock private VoucherService voucherService;
    @Mock private com.spalimited.hotspotbilling.service.tax.FiscalService fiscalService;
    @Mock private ReferralService referralService;
    @Mock private CustomPlanService customPlanService;
    @Mock private PromotionService promotionService;
    @Mock private NotificationService notificationService;
    @Mock private PortalSettingsService portalSettingsService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private WebhookService webhookService;
    @Mock private LoyaltyService loyaltyService;
    @Mock private SmsService smsService;
    @Mock private MoneyService money;
    @Mock private com.spalimited.hotspotbilling.service.payments.PaymentProviders providers;
    @Mock private com.spalimited.hotspotbilling.repository.ManualClaimRepository manualClaims;
    @Mock private PaymentGatewayService gatewayService;
    @Mock private CreditService creditService;
    @Mock private com.spalimited.hotspotbilling.repository.VoucherRepository voucherRepository;

    @InjectMocks
    private PaymentService service;

    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = Payment.builder()
                .id(7L)
                .phoneNumber("254712345678")
                .amount(new BigDecimal("50"))
                .status(Payment.Status.PENDING)
                .createdAt(Instant.now())
                .checkoutRequestId("TX-1")
                .build();

        when(paymentRepository.findByCheckoutRequestId("TX-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.findByCheckoutRequestId("")).thenReturn(Optional.empty());
        // Assigns an id the way a real save does. Needed because start() builds
        // a fresh Payment and the notification and webhook payloads read its id.
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(99L);
            }
            return p;
        });
        when(voucherService.issue(any(), any()))
                .thenReturn(Voucher.builder().code("ABC123").build());
        when(portalSettingsService.settings())
                .thenReturn(PortalSettings.builder().businessName("Test ISP").build());
    }

    @Test
    @DisplayName("A rail that answers in the same breath gets its customer online at once")
    void aSelfSettlingChargeIssuesItsVoucher() {
        // WaafiPay in Somalia. Its purchase is synchronous, it has no webhook,
        // and its own API confirms there is no status service to ask -- so this
        // is the only chance the payment gets. Before Charge carried a
        // settlement, it sat PENDING until the sweep failed a customer who had
        // paid.
        var provider = org.mockito.Mockito.mock(
                com.spalimited.hotspotbilling.service.payments.PaymentProvider.class);
        when(provider.kind()).thenReturn(
                com.spalimited.hotspotbilling.domain.PaymentGateway.Kind.WAAFIPAY);
        when(providers.chosen(any())).thenReturn(Optional.of(provider));
        when(money.code()).thenReturn("USD");
        when(planRepository.findById(any())).thenReturn(Optional.of(
                com.spalimited.hotspotbilling.domain.Plan.builder()
                        .id(3L).name("1 hour").price(new BigDecimal("1.50")).durationMinutes(60)
                        .active(true).build()));
        when(promotionService.apply(any())).thenAnswer(i -> i.getArgument(0));
        when(creditService.outstandingFor(any())).thenReturn(BigDecimal.ZERO);
        when(provider.charge(any())).thenAnswer(i -> {
            var req = (com.spalimited.hotspotbilling.service.payments.PaymentProvider.ChargeRequest)
                    i.getArgument(0);
            return new com.spalimited.hotspotbilling.service.payments.PaymentProvider.Charge(
                    "7264827", null,
                    new com.spalimited.hotspotbilling.service.payments.PaymentProvider.Settlement(
                            "7264827", req.reference(), true, new BigDecimal("1.50"), "USD",
                            "7264827", null));
        });

        Payment started = service.initiateStkPush("252611234567", 3L, "WAAFIPAY");

        assertThat(started.getStatus()).isEqualTo(Payment.Status.SUCCESS);
        assertThat(started.getVoucher()).isNotNull();
    }

    @Test
    @DisplayName("A rail that reports no amount still gets its customer online")
    void noAmountReportedStillSucceeds() {
        // Airtel and Orange Money both settle by us asking them, and neither
        // answer contains the amount. There is nothing to check and nothing
        // untrusted to check it against.
        service.settleFromProvider("Airtel", "TX-1", "TX-1", true, null, "AM-99", null);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCESS);
        assertThat(payment.getVoucher()).isNotNull();
    }

    @Test
    @DisplayName("A reported amount that differs is still refused")
    void wrongAmountStillFails() {
        // The rails that settle from a body must keep this. Flutterwave's
        // webhook is authenticated by a shared header rather than a signature
        // over the body, so the body is the part trusted least.
        service.settleFromProvider("Flutterwave", "TX-1", "TX-1", true,
                new BigDecimal("5"), "FLW-1", null);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    @Test
    @DisplayName("The right amount succeeds")
    void rightAmountSucceeds() {
        service.settleFromProvider("Paystack", "TX-1", "TX-1", true,
                new BigDecimal("50"), "PS-1", null);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.SUCCESS);
    }

    @Test
    @DisplayName("A repeat delivery does not issue a second voucher")
    void repeatIsIgnored() {
        // Every one of these providers retries until it gets a 2xx, so a repeat
        // is the normal case rather than an anomaly.
        service.settleFromProvider("Airtel", "TX-1", "TX-1", true, null, "AM-99", null);
        Voucher first = payment.getVoucher();

        service.settleFromProvider("Airtel", "TX-1", "TX-1", true, null, "AM-99", null);

        assertThat(payment.getVoucher()).isSameAs(first);
    }

    @Test
    @DisplayName("A reported failure is a failure whatever the amount says")
    void failureIsAFailure() {
        service.settleFromProvider("Airtel", "TX-1", "TX-1", false, null, null,
                "insufficient funds");

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
    }
}
