package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.CreditAdvanceRepository;
import com.spalimited.hotspotbilling.repository.CreditSettingsRepository;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Underwriting is the part of pay-later that puts the operator's money at risk,
 * so every "no" is worth pinning down — including that the customer is told
 * <em>why</em>, since "two more purchases and you can" brings somebody back
 * where a blank refusal sends them elsewhere.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreditServiceTest {

    private static final String PHONE = "254712345678";

    @Mock private CreditSettingsRepository settingsRepo;
    @Mock private CreditAdvanceRepository advances;
    @Mock private PaymentRepository payments;
    @Mock private PlanRepository plans;
    @Mock private VoucherService voucherService;
    @Mock private NotificationService notifications;
    @Mock private SmsService smsService;
    @Mock private PortalSettingsService portalSettings;
    @Mock private AuditService audit;

    private CreditService service;
    private CreditSettings settings;

    @BeforeEach
    void setUp() {
        service = new CreditService(settingsRepo, advances, payments, plans, voucherService,
                notifications, smsService, portalSettings, audit);

        settings = CreditSettings.builder()
                .id(1L)
                .enabled(true)
                .minPurchases(3)
                .minDaysKnown(7)
                .maxAdvance(BigDecimal.valueOf(100))
                .feePercent(0)
                .repayWithinHours(48)
                .maxDefaults(1)
                .build();
        when(settingsRepo.findById(anyLong())).thenReturn(Optional.of(settings));
        when(portalSettings.settings()).thenReturn(PortalSettings.builder().businessName("SPA WiFi").build());
        when(advances.findByPhoneNumberAndStatus(anyString(), any())).thenReturn(List.of());
        when(advances.countByPhoneNumberAndStatus(anyString(), any())).thenReturn(0L);
        when(payments.findByPhoneNumberOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
    }

    /** A customer with `count` successful purchases, the oldest `days` ago. */
    private void withHistory(int count, int days) {
        List<Payment> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(Payment.builder()
                    .phoneNumber(PHONE)
                    .amount(BigDecimal.valueOf(50))
                    .status(Payment.Status.SUCCESS)
                    .createdAt(Instant.now().minus(Duration.ofDays(i == 0 ? days : 1)))
                    .build());
        }
        when(payments.findByPhoneNumberOrderByCreatedAtDesc(PHONE)).thenReturn(history);
    }

    private Plan plan(long id, String name, int price) {
        return Plan.builder().id(id).name(name).price(BigDecimal.valueOf(price))
                .durationMinutes(60).type(Plan.Type.HOTSPOT)
                .availability(Plan.Availability.LIVE).active(true).build();
    }

    @Test
    @DisplayName("switched off, nobody borrows")
    void refusesWhenDisabled() {
        settings.setEnabled(false);

        CreditService.Eligibility check = service.eligibility(PHONE);

        assertThat(check.enabled()).isFalse();
        assertThat(check.eligible()).isFalse();
    }

    @Test
    @DisplayName("a stranger is told how many purchases away they are")
    void refusesNewCustomersWithACountdown() {
        CreditService.Eligibility check = service.eligibility(PHONE);

        assertThat(check.eligible()).isFalse();
        assertThat(check.reason()).isEqualTo("3 more purchases and you can pay later");
    }

    @Test
    @DisplayName("purchases alone are not enough — one good day proves nothing")
    void refusesCustomersWhoAreTooNew() {
        withHistory(5, 2);

        CreditService.Eligibility check = service.eligibility(PHONE);

        assertThat(check.eligible()).isFalse();
        assertThat(check.reason()).isEqualTo("Available after 5 more days with us");
    }

    @Test
    @DisplayName("a proven customer qualifies")
    void allowsAProvenCustomer() {
        withHistory(4, 30);

        CreditService.Eligibility check = service.eligibility(PHONE);

        assertThat(check.eligible()).isTrue();
        assertThat(check.limit()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("somebody who has already defaulted never borrows again")
    void refusesAfterADefault() {
        withHistory(10, 90);
        when(advances.countByPhoneNumberAndStatus(PHONE, CreditAdvance.Status.DEFAULTED)).thenReturn(1L);

        assertThat(service.eligibility(PHONE).eligible()).isFalse();
    }

    @Test
    @DisplayName("one advance at a time, and the balance is quoted")
    void refusesASecondAdvanceWhileOneIsOutstanding() {
        withHistory(4, 30);
        when(advances.findByPhoneNumberAndStatus(PHONE, CreditAdvance.Status.OUTSTANDING))
                .thenReturn(List.of(CreditAdvance.builder().totalDue(BigDecimal.valueOf(55)).build()));

        CreditService.Eligibility check = service.eligibility(PHONE);

        assertThat(check.eligible()).isFalse();
        assertThat(check.reason()).isEqualTo("You already have KES 55 to settle");
        assertThat(check.outstanding()).isEqualByComparingTo("55");
    }

    @Test
    @DisplayName("taking a pass records the debt with its fee and stamps its origin")
    void takingAPassRecordsTheDebt() {
        withHistory(4, 30);
        settings.setFeePercent(10);
        Plan sixHours = plan(2L, "6 Hours", 50);
        when(plans.findById(2L)).thenReturn(Optional.of(sixHours));
        when(voucherService.issue(any(), anyString(), any(), any(), anyString()))
                .thenReturn(Voucher.builder().code("ABC12345").plan(sixHours).build());
        when(advances.save(any(CreditAdvance.class))).thenAnswer(call -> call.getArgument(0));

        var result = service.take(PHONE, 2L);

        assertThat(result.get("code")).isEqualTo("ABC12345");
        assertThat(result.get("dueAmount")).isEqualTo(BigDecimal.valueOf(55));
        // Stamped so the revenue audit reads it as a pass with a reason behind it
        verify(voucherService).issue(eq(sixHours), eq(PHONE), isNull(), isNull(), eq("credit"));
    }

    @Test
    @DisplayName("a package dearer than the limit is refused")
    void refusesAPackageOverTheLimit() {
        withHistory(4, 30);
        Plan weekly = plan(9L, "Weekly", 500);
        when(plans.findById(9L)).thenReturn(Optional.of(weekly));

        assertThatThrownBy(() -> service.take(PHONE, 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("up to KES 100");
    }

    @Test
    @DisplayName("settling clears every outstanding advance and says by what")
    void settlingClearsTheDebt() {
        CreditAdvance advance = CreditAdvance.builder()
                .phoneNumber(PHONE).totalDue(BigDecimal.valueOf(55))
                .status(CreditAdvance.Status.OUTSTANDING).build();
        when(advances.findByPhoneNumberAndStatus(PHONE, CreditAdvance.Status.OUTSTANDING))
                .thenReturn(List.of(advance));
        when(advances.save(any(CreditAdvance.class))).thenAnswer(call -> call.getArgument(0));

        BigDecimal cleared = service.settle(PHONE, "Payment #7");

        assertThat(cleared).isEqualByComparingTo("55");
        assertThat(advance.getStatus()).isEqualTo(CreditAdvance.Status.REPAID);
        assertThat(advance.getRepaidNote()).isEqualTo("Payment #7");
        assertThat(advance.getRepaidAt()).isNotNull();
    }

    @Test
    @DisplayName("settling an unknown number is harmless")
    void settlingNothingIsSafe() {
        assertThat(service.settle("not a phone number", "x")).isEqualByComparingTo("0");
        assertThat(service.settle(null, "x")).isEqualByComparingTo("0");
    }
}
