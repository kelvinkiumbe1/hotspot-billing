package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When a pass starts running down.
 *
 * <p>A pass sold to somebody starts at the moment they pay: buy six hours at
 * nine and it runs to three, whether or not you connect. Stock generated for
 * an agent to resell cannot work that way — it has no buyer yet, and a clock
 * started at generation would make Monday's inventory worthless by Tuesday.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoucherServiceTest {

    @Mock private VoucherRepository voucherRepository;
    @Mock private MikrotikService mikrotikService;
    @Mock private HotspotSettingsService hotspotSettings;

    private VoucherService service;
    private Plan sixHours;
    private final List<Voucher> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new VoucherService(voucherRepository, mikrotikService, hotspotSettings);
        sixHours = Plan.builder().id(1L).name("6 Hours").durationMinutes(360)
                .price(new BigDecimal("50")).build();

        when(voucherRepository.existsByCode(anyString())).thenReturn(false);
        when(voucherRepository.save(any(Voucher.class))).thenAnswer(i -> {
            Voucher v = i.getArgument(0);
            saved.add(v);
            return v;
        });
    }

    @Test
    @DisplayName("A pass sold to somebody starts running the moment they pay")
    void soldPassStartsAtPurchase() {
        Voucher v = service.issue(sixHours, "254757306837");

        assertThat(v.getExpiresAt()).isNotNull();
        long minutes = ChronoUnit.MINUTES.between(Instant.now(), v.getExpiresAt());
        assertThat(minutes).isBetween(358L, 360L);
        // It has not been used yet — the clock running is not the same as
        // somebody being online.
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.UNUSED);
        assertThat(v.getActivatedAt()).isNull();
    }

    @Test
    @DisplayName("A pay-per-minute pass does the same, on its own duration")
    void customDurationStartsAtPurchase() {
        Voucher v = service.issueCustom(sixHours, "254757306837", 45);

        assertThat(ChronoUnit.MINUTES.between(Instant.now(), v.getExpiresAt()))
                .isBetween(43L, 45L);
    }

    @Test
    @DisplayName("Agent stock has no buyer, so no clock — it must survive on the shelf")
    void batchStockDoesNotStartCountingDown() {
        Voucher v = service.issue(sixHours, null);

        assertThat(v.getExpiresAt()).isNull();
        assertThat(v.getStatus()).isEqualTo(Voucher.Status.UNUSED);
    }

    @Test
    @DisplayName("Stock starts counting when the customer first uses it")
    void batchStockStartsOnFirstUse() {
        Voucher stock = Voucher.builder().code("AGENT001").plan(sixHours)
                .status(Voucher.Status.UNUSED).build();
        when(voucherRepository.findByCode("AGENT001")).thenReturn(Optional.of(stock));

        Voucher activated = service.activate("AGENT001");

        assertThat(activated.getActivatedAt()).isNotNull();
        assertThat(ChronoUnit.MINUTES.between(Instant.now(), activated.getExpiresAt()))
                .isBetween(358L, 360L);
    }

    @Test
    @DisplayName("Using a sold pass does not restart its clock")
    void usingASoldPassDoesNotExtendIt() {
        Instant deadline = Instant.now().plus(Duration.ofHours(2));
        Voucher sold = Voucher.builder().code("SOLD0001").plan(sixHours)
                .phoneNumber("254757306837").status(Voucher.Status.UNUSED)
                .expiresAt(deadline).build();
        when(voucherRepository.findByCode("SOLD0001")).thenReturn(Optional.of(sold));

        Voucher activated = service.activate("SOLD0001");

        // Four hours were already spent not connecting. Resetting here would
        // hand them back, and make hoarding cheap passes free.
        assertThat(activated.getExpiresAt()).isEqualTo(deadline);
    }

    @Test
    @DisplayName("A pass past its deadline is expired and removed from the router")
    void cutsOffAPassThatRanOutOfTime() {
        Voucher lapsed = Voucher.builder().code("SOLD0002").plan(sixHours)
                .phoneNumber("254757306837").status(Voucher.Status.ACTIVE)
                .expiresAt(Instant.now().minus(Duration.ofMinutes(5))).build();
        when(voucherRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(lapsed));

        assertThat(service.expirePastDeadline()).isEqualTo(1);

        assertThat(lapsed.getStatus()).isEqualTo(Voucher.Status.EXPIRED);
        // Marking it expired without this leaves them online on leftover
        // uptime credit, and the revenue audit then reports our own pass.
        verify(mikrotikService).removeVoucher(lapsed);
    }

    @Test
    @DisplayName("An unreachable router does not stop the rest being expired")
    void carriesOnWhenTheRouterIsDown() {
        Voucher a = Voucher.builder().code("A").plan(sixHours).status(Voucher.Status.ACTIVE)
                .expiresAt(Instant.now().minus(Duration.ofMinutes(5))).build();
        Voucher b = Voucher.builder().code("B").plan(sixHours).status(Voucher.Status.ACTIVE)
                .expiresAt(Instant.now().minus(Duration.ofMinutes(5))).build();
        when(voucherRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new IllegalStateException("router unreachable"))
                .when(mikrotikService).removeVoucher(a);

        assertThat(service.expirePastDeadline()).isEqualTo(2);

        assertThat(a.getStatus()).isEqualTo(Voucher.Status.EXPIRED);
        assertThat(b.getStatus()).isEqualTo(Voucher.Status.EXPIRED);
        verify(mikrotikService).removeVoucher(b);
    }

    @Test
    @DisplayName("Nothing is touched while it still has time on it")
    void leavesLivePassesAlone() {
        when(voucherRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of());

        assertThat(service.expirePastDeadline()).isZero();
        verify(mikrotikService, never()).removeVoucher(any());
    }

    // --- What the customer can see and do with their own pass ---

    private Voucher live(long usedSeconds, Instant expiresAt, long usedBytes) {
        return Voucher.builder().id(9L).code("ABCD1234").plan(sixHours)
                .phoneNumber("254757306837").status(Voucher.Status.ACTIVE)
                .usedSeconds(usedSeconds).usedBytes(usedBytes).expiresAt(expiresAt).build();
    }

    @Test
    @DisplayName("Time left is whichever runs out first — the clock or the connect-time")
    void reportsWhicheverLimitBitesFirst() {
        // Six hours bought, one used, but only 40 minutes of wall-clock left.
        VoucherService.PassStatus tight = service.statusOf(
                live(3600, Instant.now().plus(Duration.ofMinutes(40)), 0));
        assertThat(tight.minutesLeft()).isBetween(38L, 40L);

        // Plenty of clock, but five and a half hours of connect-time spent.
        VoucherService.PassStatus spent = service.statusOf(
                live(19_800, Instant.now().plus(Duration.ofHours(20)), 0));
        assertThat(spent.minutesLeft()).isBetween(29L, 30L);
    }

    @Test
    @DisplayName("A capped package reports what data is left, an uncapped one does not invent a cap")
    void reportsDataAgainstTheCap() {
        sixHours.setDataLimitMb(2000);
        VoucherService.PassStatus capped = service.statusOf(
                live(0, Instant.now().plus(Duration.ofHours(5)), 512L * 1_048_576L));
        assertThat(capped.usedMb()).isEqualTo(512);
        assertThat(capped.capMb()).isEqualTo(2000);
        assertThat(capped.mbLeft()).isEqualTo(1488);

        sixHours.setDataLimitMb(null);
        VoucherService.PassStatus uncapped = service.statusOf(
                live(0, Instant.now().plus(Duration.ofHours(5)), 512L * 1_048_576L));
        assertThat(uncapped.capMb()).isNull();
        assertThat(uncapped.mbLeft()).isNull();
    }

    @Test
    @DisplayName("Going over the cap shows nothing left rather than a negative figure")
    void neverShowsNegativeData() {
        sixHours.setDataLimitMb(100);
        VoucherService.PassStatus over = service.statusOf(
                live(0, Instant.now().plus(Duration.ofHours(5)), 300L * 1_048_576L));
        assertThat(over.mbLeft()).isZero();
    }

    @Test
    @DisplayName("Signing out drops the device but keeps the pass and its time")
    void signOutKeepsThePass() {
        Voucher v = live(3600, Instant.now().plus(Duration.ofHours(5)), 0);
        when(mikrotikService.kickSessions(v)).thenReturn(true);

        assertThat(service.signOutDevices(v)).isTrue();

        assertThat(v.getStatus()).isEqualTo(Voucher.Status.ACTIVE);
        assertThat(v.getUsedSeconds()).isEqualTo(3600);
        verify(mikrotikService, never()).removeVoucher(any());
    }

    @Test
    @DisplayName("Reissuing gives a new code, kills the old one, and carries the balance over")
    void reissueCarriesTheBalance() {
        Voucher v = live(3600, Instant.now().plus(Duration.ofHours(5)), 200L * 1_048_576L);
        v.setRouterUptimeSeconds(3600);

        Voucher fresh = service.reissueUnderNewCode(v);

        assertThat(fresh.getCode()).isNotEqualTo("ABCD1234").hasSize(8);
        // The old one is removed from the router first, so the two codes are
        // never live at the same time.
        verify(mikrotikService).removeVoucher(v);
        // The replacement carries only what was left: five hours, not six.
        verify(mikrotikService).provisionVoucher(eq(fresh), intThat(m -> m >= 298 && m <= 300));
        // Usage history stays on the pass; the router counter restarts because
        // the new user on the router starts from zero uptime.
        assertThat(fresh.getUsedSeconds()).isEqualTo(3600);
        assertThat(fresh.getUsedBytes()).isEqualTo(200L * 1_048_576L);
        assertThat(fresh.getRouterUptimeSeconds()).isZero();
    }

    @Test
    @DisplayName("If the old code cannot be cancelled, nothing changes — two live codes is the bug")
    void reissueRefusesWhenTheOldCodeSurvives() {
        Voucher v = live(3600, Instant.now().plus(Duration.ofHours(5)), 0);
        org.mockito.Mockito.doThrow(new IllegalStateException("router unreachable"))
                .when(mikrotikService).removeVoucher(v);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reissueUnderNewCode(v))
                .hasMessageContaining("nothing has changed");

        assertThat(v.getCode()).isEqualTo("ABCD1234");
        verify(mikrotikService, never()).provisionVoucher(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("A finished pass cannot be reissued or signed out")
    void refusesToWorkOnAFinishedPass() {
        Voucher done = Voucher.builder().id(9L).code("ABCD1234").plan(sixHours)
                .status(Voucher.Status.EXPIRED).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reissueUnderNewCode(done))
                .hasMessageContaining("already finished");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.signOutDevices(done))
                .hasMessageContaining("already finished");
    }
}
