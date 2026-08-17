package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.PayCodeRepository;
import com.spalimited.hotspotbilling.repository.PaybillSettingsRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Turning a bare paybill payment into a pass means guessing what the customer
 * wanted from an amount and a phone number, so the guardrails around that guess
 * are what these cover: which package the money buys, what happens when it buys
 * nothing, and when the system should decline to guess at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaybillActivationServiceTest {

    private static final String PHONE = "254712345678";

    @Mock private PayCodeRepository payCodes;
    @Mock private PaybillSettingsRepository settingsRepo;
    @Mock private PlanRepository plans;
    @Mock private RouterRepository routers;
    @Mock private VoucherService voucherService;
    @Mock private VoucherRepository vouchers;
    @Mock private MikrotikService mikrotik;
    @Mock private NotificationService notifications;
    @Mock private SmsService smsService;
    @Mock private PortalSettingsService portalSettings;
    @Mock private PaymentGatewayService gateways;
    @Mock private CreditService credit;
    @Mock private AuditService audit;
    @Mock private MoneyService money;

    private PaybillActivationService service;
    private PaybillSettings settings;

    private final Plan customTime = plan(1L, CustomPlanService.SYSTEM_PLAN_NAME, 1);
    private final Plan oneHour = plan(2L, "1 Hour", 20);
    private final Plan sixHours = plan(3L, "6 Hours", 50);
    private final Plan daily = plan(4L, "24 Hours", 100);

    @BeforeEach
    void setUp() {
        service = new PaybillActivationService(payCodes, settingsRepo, plans, routers, voucherService,
                vouchers, mikrotik, notifications, smsService, portalSettings, money, gateways, credit, audit);
        // The mock formats the way Kenya does, so the wording these tests
        // assert on reads exactly as it did before currency became a setting.
        when(money.format(org.mockito.ArgumentMatchers.any())).thenAnswer(
                i -> "KES " + (i.getArgument(0) == null ? "0"
                        : ((java.math.BigDecimal) i.getArgument(0)).stripTrailingZeros().toPlainString()));


        settings = PaybillSettings.builder()
                .id(1L).enabled(true).autoLoginByMac(false)
                .payCodeMinutes(120).notifyOnShortfall(true)
                .maxAmount(BigDecimal.valueOf(3000))
                .build();
        when(settingsRepo.findById(anyLong())).thenReturn(Optional.of(settings));
        when(portalSettings.settings()).thenReturn(PortalSettings.builder().businessName("SPA WiFi").build());
        when(plans.findByActiveTrueOrderByPriceAsc())
                .thenReturn(List.of(customTime, oneHour, sixHours, daily));
        when(credit.outstandingFor(anyString())).thenReturn(BigDecimal.ZERO);
        when(voucherService.issue(any(), anyString(), any(), any(), anyString()))
                .thenAnswer(call -> Voucher.builder().code("CODE1234").plan(call.getArgument(0)).build());
    }

    private static Plan plan(long id, String name, int price) {
        return Plan.builder().id(id).name(name).price(BigDecimal.valueOf(price))
                .durationMinutes(60).type(Plan.Type.HOTSPOT)
                .availability(Plan.Availability.LIVE).active(true).build();
    }

    @Test
    @DisplayName("the money buys the best package it covers, not the cheapest")
    void picksTheDearestAffordablePackage() {
        var outcome = service.activate(BigDecimal.valueOf(60), PHONE, null);

        assertThat(outcome.activated()).isTrue();
        assertThat(outcome.planName()).isEqualTo("6 Hours");
    }

    @Test
    @DisplayName("an exact amount buys exactly that package")
    void picksTheExactPackage() {
        assertThat(service.activate(BigDecimal.valueOf(100), PHONE, null).planName()).isEqualTo("24 Hours");
    }

    @Test
    @DisplayName("the pay-per-minute holder row is never sold")
    void neverSellsTheSystemPlan() {
        // KES 3 covers only the KES 1 "Custom Time" row, which exists solely to
        // hang custom payments off and is not a package anybody may buy.
        var outcome = service.activate(BigDecimal.valueOf(3), PHONE, null);

        assertThat(outcome.activated()).isFalse();
        assertThat(outcome.note()).contains("does not cover any hotspot plan");
        verify(smsService).trySend(eq(PHONE), contains("less than our"));
    }

    @Test
    @DisplayName("too little money is answered, not ignored")
    void tellsThemWhenItIsShort() {
        var outcome = service.activate(BigDecimal.valueOf(5), PHONE, null);

        assertThat(outcome.activated()).isFalse();
        verify(smsService).trySend(eq(PHONE), contains("KES 5"));
    }

    @Test
    @DisplayName("a large payment from a stranger is left for a human")
    void refusesToGuessAboveTheCeiling() {
        var outcome = service.activate(BigDecimal.valueOf(5000), PHONE, null);

        assertThat(outcome.activated()).isFalse();
        assertThat(outcome.note()).contains("above the auto-issue limit");
        verifyNoInteractions(voucherService);
    }

    @Test
    @DisplayName("a pay-later debt comes off the top before the pass is chosen")
    void settlesCreditFirst() {
        when(credit.outstandingFor(PHONE)).thenReturn(BigDecimal.valueOf(55));

        // 110 in, 55 owed: 55 left, which buys the 6 Hours pass and not the 24.
        var outcome = service.activate(BigDecimal.valueOf(110), PHONE, null);

        assertThat(outcome.activated()).isTrue();
        assertThat(outcome.planName()).isEqualTo("6 Hours");
        verify(credit).settle(eq(PHONE), contains("110"));
    }

    @Test
    @DisplayName("money that cannot even clear the debt is not part-applied")
    void leavesPartPaymentsAlone() {
        when(credit.outstandingFor(PHONE)).thenReturn(BigDecimal.valueOf(55));

        var outcome = service.activate(BigDecimal.valueOf(30), PHONE, null);

        assertThat(outcome.activated()).isFalse();
        assertThat(outcome.note()).contains("not enough to settle");
        verify(credit, never()).settle(anyString(), anyString());
        verifyNoInteractions(voucherService);
    }

    @Test
    @DisplayName("a pay code ties the payment to the device that was shown it")
    void matchesOnThePayCode() {
        PayCode code = PayCode.builder()
                .code("AB12CD").macAddress("AA:BB:CC:DD:EE:FF").routerId(7L)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .build();
        when(payCodes.findById("AB12CD")).thenReturn(Optional.of(code));
        when(payCodes.save(any(PayCode.class))).thenAnswer(call -> call.getArgument(0));

        // Typed sloppily, as it will be: lower case, with a space.
        var outcome = service.activate(BigDecimal.valueOf(60), PHONE, "ab 12cd");

        assertThat(outcome.activated()).isTrue();
        assertThat(outcome.note()).contains("against pay code AB12CD");
        assertThat(code.getUsedAt()).isNotNull();
        assertThat(code.getVoucherCode()).isEqualTo("CODE1234");
    }

    @Test
    @DisplayName("a spent pay code falls back to matching on the phone")
    void ignoresASpentPayCode() {
        PayCode spent = PayCode.builder()
                .code("AB12CD").macAddress("AA:BB:CC:DD:EE:FF")
                .createdAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .usedAt(Instant.now())
                .build();
        when(payCodes.findById("AB12CD")).thenReturn(Optional.of(spent));

        var outcome = service.activate(BigDecimal.valueOf(60), PHONE, "AB12CD");

        assertThat(outcome.activated()).isTrue();
        assertThat(outcome.note()).contains("matched by phone");
    }

    @Test
    @DisplayName("switched off, a paybill payment is left alone")
    void doesNothingWhenDisabled() {
        settings.setEnabled(false);

        assertThat(service.activate(BigDecimal.valueOf(60), PHONE, null).activated()).isFalse();
        verifyNoInteractions(voucherService);
    }

    @Test
    @DisplayName("no paying number means no way to deliver a code")
    void refusesWithoutAPhoneNumber() {
        var outcome = service.activate(BigDecimal.valueOf(60), "", null);

        assertThat(outcome.activated()).isFalse();
        assertThat(outcome.note()).contains("No paying number");
    }
}
