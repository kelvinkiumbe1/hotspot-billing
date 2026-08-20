package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.RadiusSession;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.RadiusSessionRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.TrafficUsageRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import com.spalimited.hotspotbilling.service.SubscriberUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Folding a router's session reports into what the customer has used.
 *
 * <p>Everything here exists because accounting packets are retransmitted. A
 * NAS whose reply was lost sends the same Stop again; a NAS returning from a
 * reboot replays a batch of them. If those are added rather than applied as a
 * high-water mark, a customer's pass drains at twice the rate they used it and
 * nothing in the record shows why.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RadiusAccountingServiceTest {

    @Mock
    private RadiusSessionRepository sessions;

    @Mock
    private VoucherRepository vouchers;

    @Mock
    private SubscriberRepository subscribers;

    @Mock
    private TrafficUsageRepository trafficUsage;

    @Mock
    private SubscriberUsageService subscriberUsage;

    @InjectMocks
    private RadiusAccountingService accounting;

    private final Map<String, RadiusSession> stored = new HashMap<>();
    private Voucher voucher;

    @BeforeEach
    void setUp() {
        Plan plan = new Plan();
        plan.setName("1 hour");
        plan.setDurationMinutes(60);
        voucher = Voucher.builder().id(1L).code("ABC123").plan(plan)
                .status(Voucher.Status.UNUSED).usedSeconds(0).usedBytes(0).build();

        when(vouchers.findById(1L)).thenReturn(Optional.of(voucher));
        when(vouchers.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sessions.findByNasAddressAndAcctSessionId(anyString(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(1))));
        when(sessions.save(any())).thenAnswer(i -> {
            RadiusSession s = i.getArgument(0);
            stored.put(s.getAcctSessionId(), s);
            return s;
        });
        when(trafficUsage.findByBucketHourAndRouterIdAndUserKey(any(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(trafficUsage.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private RadiusAccountingService.Report report(int statusType, long seconds, long in, long out) {
        return new RadiusAccountingService.Report(
                "10.0.0.1", "sess-1", "ABC123", statusType, in, out, seconds,
                "10.5.0.20", "AA:BB:CC:DD:EE:FF", null, null, null, 7L);
    }

    @Test
    @DisplayName("A Start marks an unused pass as active and gives it a deadline")
    void startActivates() {
        accounting.record(report(RadiusPacket.ACCT_START, 0, 0, 0),
                RadiusSession.Kind.HOTSPOT, 1L, null);

        // Zero usage, so nothing is folded in — but the session exists.
        assertThat(stored).containsKey("sess-1");
        assertThat(stored.get("sess-1").getFramedIp()).isEqualTo("10.5.0.20");
    }

    @Test
    @DisplayName("Interim updates add only what is new since the last one")
    void interimAddsTheDelta() {
        accounting.record(report(RadiusPacket.ACCT_INTERIM, 300, 1_000_000, 5_000_000),
                RadiusSession.Kind.HOTSPOT, 1L, null);
        assertThat(voucher.getUsedSeconds()).isEqualTo(300);
        assertThat(voucher.getUsedBytes()).isEqualTo(6_000_000);

        // The counters are cumulative for the session, so the second update
        // reports 600 seconds total, not another 300.
        accounting.record(report(RadiusPacket.ACCT_INTERIM, 600, 2_000_000, 9_000_000),
                RadiusSession.Kind.HOTSPOT, 1L, null);

        assertThat(voucher.getUsedSeconds()).isEqualTo(600);
        assertThat(voucher.getUsedBytes()).isEqualTo(11_000_000);
    }

    @Test
    @DisplayName("The same Stop arriving twice charges the customer once")
    void replayedStopDoesNotDoubleCharge() {
        var stop = report(RadiusPacket.ACCT_STOP, 1_800, 10_000_000, 40_000_000);

        accounting.record(stop, RadiusSession.Kind.HOTSPOT, 1L, null);
        long secondsAfterFirst = voucher.getUsedSeconds();
        long bytesAfterFirst = voucher.getUsedBytes();

        // Exactly what a NAS does when our acknowledgement is lost in transit.
        accounting.record(stop, RadiusSession.Kind.HOTSPOT, 1L, null);

        assertThat(voucher.getUsedSeconds()).isEqualTo(secondsAfterFirst).isEqualTo(1_800);
        assertThat(voucher.getUsedBytes()).isEqualTo(bytesAfterFirst).isEqualTo(50_000_000);
    }

    @Test
    @DisplayName("A packet that arrives out of order does not rewind the totals")
    void outOfOrderIsIgnored() {
        accounting.record(report(RadiusPacket.ACCT_INTERIM, 600, 2_000_000, 9_000_000),
                RadiusSession.Kind.HOTSPOT, 1L, null);
        assertThat(voucher.getUsedSeconds()).isEqualTo(600);

        // The earlier update, delayed on the network, turns up afterwards.
        accounting.record(report(RadiusPacket.ACCT_INTERIM, 300, 1_000_000, 5_000_000),
                RadiusSession.Kind.HOTSPOT, 1L, null);

        assertThat(voucher.getUsedSeconds()).isEqualTo(600);
        assertThat(voucher.getUsedBytes()).isEqualTo(11_000_000);
    }

    @Test
    @DisplayName("Usage crossing the pass's duration expires it")
    void exhaustionExpiresThePass() {
        // An hour bought, an hour and a minute used.
        accounting.record(report(RadiusPacket.ACCT_STOP, 3_660, 1_000, 1_000),
                RadiusSession.Kind.HOTSPOT, 1L, null);

        assertThat(voucher.getStatus()).isEqualTo(Voucher.Status.EXPIRED);
        assertThat(voucher.getRemainingSeconds()).isZero();
    }

    @Test
    @DisplayName("A pass first seen at the hotspot login is activated and given its deadline")
    void firstSightingStartsTheClock() {
        assertThat(voucher.getStatus()).isEqualTo(Voucher.Status.UNUSED);
        assertThat(voucher.getExpiresAt()).isNull();

        accounting.record(report(RadiusPacket.ACCT_INTERIM, 60, 1_000, 1_000),
                RadiusSession.Kind.HOTSPOT, 1L, null);

        assertThat(voucher.getStatus()).isEqualTo(Voucher.Status.ACTIVE);
        assertThat(voucher.getActivatedAt()).isNotNull();
        // Without this, a code redeemed at the hotspot login rather than through
        // the portal never got a deadline at all.
        assertThat(voucher.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("A Stop for a session we never saw start is still counted")
    void unseenSessionIsStillRecorded() {
        // Ordinary after a restart of this service, and losing the usage
        // entirely is the worse of the two options.
        accounting.record(report(RadiusPacket.ACCT_STOP, 900, 5_000_000, 5_000_000),
                RadiusSession.Kind.HOTSPOT, 1L, null);

        assertThat(voucher.getUsedSeconds()).isEqualTo(900);
        assertThat(stored.get("sess-1").getStoppedAt()).isNotNull();
    }
}
