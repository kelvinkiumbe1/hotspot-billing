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
    @Mock private EtimsService etimsService;
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
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(voucherService.issue(any(), any()))
                .thenReturn(Voucher.builder().code("ABC123").build());
        when(portalSettingsService.settings())
                .thenReturn(PortalSettings.builder().businessName("Test ISP").build());
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
