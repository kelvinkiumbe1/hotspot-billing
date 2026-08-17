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
}
