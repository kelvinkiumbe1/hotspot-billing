package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CapacitySettings;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.TrafficUsage;
import com.spalimited.hotspotbilling.repository.CapacitySettingsRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.TrafficUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Capacity planning. The judgements worth pinning down are the cautious ones:
 * refusing to call a busy hour from three days of data, refusing to read one
 * catch-up spike as a full link, and refusing to project a trend that has not
 * had time to be one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CapacityServiceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Mock private CapacitySettingsRepository settingsRepo;
    @Mock private TrafficUsageRepository traffic;
    @Mock private RouterRepository routers;
    @Mock private SmsService smsService;
    @Mock private OperatorAlertService operatorAlerts;
    @Mock private MessagingSettingsService messagingSettings;

    private CapacityService service;
    private CapacitySettings settings;
    private Router kilimani;

    @BeforeEach
    void setUp() {
        service = new CapacityService(settingsRepo, traffic, routers, smsService, operatorAlerts,
                messagingSettings);

        settings = CapacitySettings.builder().id(1L).enabled(true).lookbackDays(28).build();
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(settings));
        when(settingsRepo.save(any(CapacitySettings.class))).thenAnswer(i -> i.getArgument(0));

        kilimani = Router.builder().id(1L).name("Kilimani").location("Kilimani")
                .enabled(true).capacityMbps(100).build();
        when(routers.findByEnabledTrue()).thenReturn(List.of(kilimani));
        when(routers.findAll()).thenReturn(List.of(kilimani));
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(List.of());
        when(messagingSettings.alertPhone()).thenReturn("254700999888");
    }

    /** Bytes that, spread over one hour, come to the given Mbps. */
    private static long bytesFor(double mbps) {
        return (long) (mbps * 1_000_000L * 3600L / 8L);
    }

    private static TrafficUsage row(LocalDate date, int hour, double mbps, String user) {
        return TrafficUsage.builder()
                .bucketHour(LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(ZONE).toInstant())
                .routerId(1L)
                .userKey(user)
                .bytesDown(bytesFor(mbps))
                .bytesUp(0)
                .build();
    }

    /** A steady site: every day the same shape, peaking at the given Mbps. */
    private List<TrafficUsage> steady(int days, double peakMbps) {
        List<TrafficUsage> rows = new ArrayList<>();
        for (int d = 1; d <= days; d++) {
            LocalDate date = LocalDate.now().minusDays(d);
            for (int h = 0; h < 24; h++) {
                rows.add(row(date, h, h >= 19 && h <= 22 ? peakMbps : peakMbps / 10, "u" + (h % 3)));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("Three days of traffic is not a busy hour, and says so")
    void refusesToJudgeFromThinData() {
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(3, 80));

        CapacityService.SiteOutlook site = service.outlook().sites().get(0);

        assertThat(site.verdict()).isEqualTo("UNKNOWN");
        assertThat(site.usedPercent()).isNull();
        assertThat(site.advice()).contains("3 day(s)");
    }

    @Test
    @DisplayName("A site with no stated capacity asks for it rather than guessing")
    void asksForTheDenominator() {
        kilimani.setCapacityMbps(null);
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(14, 80));

        CapacityService.SiteOutlook site = service.outlook().sites().get(0);

        assertThat(site.verdict()).isEqualTo("UNKNOWN");
        assertThat(site.advice()).contains("Set what this link can carry");
        assertThat(site.busyHourMbps()).isGreaterThan(0);
    }

    @Test
    @DisplayName("A full link is called full")
    void spotsAFullSite() {
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(14, 95));

        CapacityService.SiteOutlook site = service.outlook().sites().get(0);

        assertThat(site.verdict()).isEqualTo("CRITICAL");
        assertThat(site.usedPercent()).isGreaterThanOrEqualTo(90);
        assertThat(site.advice()).contains("backhaul");
    }

    @Test
    @DisplayName("A quiet link is flagged as capacity bought and not sold")
    void spotsWastedCapacity() {
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(14, 12));

        CapacityService.SiteOutlook site = service.outlook().sites().get(0);

        assertThat(site.verdict()).isEqualTo("UNDERUSED");
        assertThat(site.advice()).contains("sell into");
    }

    @Test
    @DisplayName("One catch-up spike does not make a site look full")
    void ignoresASingleSpike() {
        List<TrafficUsage> rows = new ArrayList<>(steady(14, 20));
        // A router comes back from an outage and pushes a backlog through one hour.
        rows.add(row(LocalDate.now().minusDays(5), 3, 400, "catchup"));
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(rows);

        CapacityService.SiteOutlook site = service.outlook().sites().get(0);

        // The maximum would read 400 Mbps on a 100 Mbps link; the 95th
        // percentile knows better.
        assertThat(site.busyHourMbps()).isLessThan(100);
        assertThat(site.verdict()).isNotEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("Growth is projected forward, and a site filling soon is warned about early")
    void seesItComing() {
        List<TrafficUsage> rows = new ArrayList<>();
        // Four weeks of steady growth: quiet a month ago, busy now, but still
        // under the warning line today.
        for (int d = 28; d >= 1; d--) {
            LocalDate date = LocalDate.now().minusDays(d);
            double peak = 30 + (28 - d) * 1.0;
            for (int h = 0; h < 24; h++) {
                rows.add(row(date, h, h >= 19 && h <= 22 ? peak : peak / 10, "u"));
            }
        }
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(rows);

        CapacityService.SiteOutlook site = service.outlook().sites().get(0);

        assertThat(site.weeklyGrowthMbps()).isGreaterThan(0);
        assertThat(site.weeksUntilFull()).isNotNull();
        assertThat(site.usedPercent()).isLessThan(settings.getWarnPercent());
    }

    @Test
    @DisplayName("A flat site is not given a date it will never reach")
    void doesNotProjectAFlatTrend() {
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(28, 40));

        CapacityService.SiteOutlook site = service.outlook().sites().get(0);

        assertThat(site.verdict()).isEqualTo("OK");
        assertThat(site.weeksUntilFull()).isNull();
    }

    @Test
    @DisplayName("The heaviest users are ranked with their share of the site")
    void ranksHeavyUsers() {
        List<TrafficUsage> rows = new ArrayList<>(steady(14, 40));
        // The ordinary users in steady() already carry a couple of peak hours
        // each, so a heavy user has to be genuinely heavy to top them.
        for (int d = 1; d <= 14; d++) {
            for (int h = 10; h <= 15; h++) {
                rows.add(row(LocalDate.now().minusDays(d), h, 200, "hog"));
            }
        }
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(rows);

        List<CapacityService.HeavyUser> heaviest = service.outlook().heaviest();

        assertThat(heaviest).isNotEmpty();
        assertThat(heaviest.get(0).userKey()).isEqualTo("hog");
        assertThat(heaviest.get(0).shareOfSitePercent()).isGreaterThan(0);
    }

    @Test
    @DisplayName("A quiet week is a silent week")
    void saysNothingWhenThereIsNothingToSay() {
        settings.setNotify(true);
        settings.setNotifyDayOfWeek(LocalDate.now(ZONE).getDayOfWeek().getValue());
        settings.setNotifyHour(LocalTime.now(ZONE).getHour());
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(14, 30));

        assertThat(service.maybeNotify()).isZero();
        org.mockito.Mockito.verify(smsService, org.mockito.Mockito.never())
                .trySend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("A site under pressure gets exactly one message that day")
    void warnsOncePerWeek() {
        settings.setNotify(true);
        settings.setNotifyDayOfWeek(LocalDate.now(ZONE).getDayOfWeek().getValue());
        settings.setNotifyHour(LocalTime.now(ZONE).getHour());
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(14, 95));

        assertThat(service.maybeNotify()).isEqualTo(1);
        assertThat(service.maybeNotify()).isZero();
        // Once, through the operator alert channel. The point of the test is the
        // once-per-week gate, not which channel carried it.
        org.mockito.Mockito.verify(operatorAlerts, org.mockito.Mockito.times(1))
                .alert(org.mockito.ArgumentMatchers.contains("Kilimani"));
    }

    @Test
    @DisplayName("Switched off, it does not text anybody")
    void respectsTheOffSwitch() {
        settings.setEnabled(false);
        settings.setNotify(true);
        settings.setNotifyDayOfWeek(LocalDate.now(ZONE).getDayOfWeek().getValue());
        settings.setNotifyHour(LocalTime.now(ZONE).getHour());
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(steady(14, 95));

        assertThat(service.maybeNotify()).isZero();
    }

    @Test
    @DisplayName("The full line can never be set below the warning line")
    void keepsThresholdsInOrder() {
        CapacitySettings saved = service.update(CapacitySettings.builder()
                .id(1L).warnPercent(80).criticalPercent(50).underusedPercent(20)
                .lookbackDays(28).notifyDayOfWeek(1).notifyHour(8).build());

        assertThat(saved.getCriticalPercent()).isGreaterThan(saved.getWarnPercent());
    }
}
