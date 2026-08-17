package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.OfferNotice;
import com.spalimited.hotspotbilling.domain.OffpeakSettings;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.Promotion;
import com.spalimited.hotspotbilling.domain.TrafficUsage;
import com.spalimited.hotspotbilling.repository.OfferNoticeRepository;
import com.spalimited.hotspotbilling.repository.OffpeakSettingsRepository;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PromotionRepository;
import com.spalimited.hotspotbilling.repository.TrafficUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Off-peak offers. Two things must hold whatever the data says: a discount the
 * operator started by hand is never touched, and two discounts never stack.
 * Everything else is about picking hours that are genuinely empty.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OffPeakServiceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Mock private OffpeakSettingsRepository settingsRepo;
    @Mock private TrafficUsageRepository traffic;
    @Mock private PaymentRepository payments;
    @Mock private PromotionRepository promotions;
    @Mock private OfferNoticeRepository notices;
    @Mock private AudienceService audiences;
    @Mock private SmsService smsService;
    @Mock private PortalSettingsService portalSettings;

    private OffPeakService service;

    private final List<Promotion> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private OffpeakSettings settings;

    @BeforeEach
    void setUp() {
        service = new OffPeakService(settingsRepo, traffic, payments, promotions, notices,
                audiences, smsService, portalSettings);

        settings = OffpeakSettings.builder().id(1L).enabled(true).autoWindow(false).build();
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(settings));
        when(settingsRepo.save(any(OffpeakSettings.class))).thenAnswer(i -> i.getArgument(0));

        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(List.of());
        when(payments.findByStatusAndCreatedAtAfter(any(), any())).thenReturn(List.of());
        when(audiences.forSegment(anyString())).thenReturn(List.of());
        when(notices.findByKindAndSentAtAfter(anyString(), any())).thenReturn(List.of());
        when(portalSettings.settings()).thenReturn(PortalSettings.builder().businessName("SPA WiFi").build());

        when(promotions.save(any(Promotion.class))).thenAnswer(i -> {
            Promotion p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(ids.getAndIncrement());
                p.setCreatedAt(Instant.now());
                stored.add(p);
            }
            return p;
        });
        when(promotions.findTop20ByOrderByCreatedAtDesc()).thenAnswer(i -> List.copyOf(stored));
    }

    /** Puts the configured window around right now, so sync() is inside it. */
    private void windowIsOpenNow() {
        int hour = LocalTime.now(ZONE).getHour();
        settings.setWindowStartHour(hour);
        settings.setWindowEndHour((hour + 4) % 24);
    }

    private void windowIsClosedNow() {
        int hour = LocalTime.now(ZONE).getHour();
        settings.setWindowStartHour((hour + 3) % 24);
        settings.setWindowEndHour((hour + 6) % 24);
    }

    private Promotion running(String source) {
        Promotion p = Promotion.builder()
                .id(ids.getAndIncrement())
                .title(source + " offer")
                .discountPercent(10)
                .startsAt(Instant.now().minus(Duration.ofHours(1)))
                .endsAt(Instant.now().plus(Duration.ofHours(5)))
                .source(source)
                .createdAt(Instant.now())
                .build();
        stored.add(p);
        return p;
    }

    @Test
    @DisplayName("The offer opens inside the quiet hours and closes outside them")
    void opensAndClosesItself() {
        windowIsOpenNow();
        assertThat(service.sync()).containsEntry("offerRunning", true);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getSource()).isEqualTo(Promotion.SOURCE_OFFPEAK);

        windowIsClosedNow();
        assertThat(service.sync()).containsEntry("offerRunning", false);
        assertThat(stored.get(0).getEndsAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("A second sync inside the same window does not open a second offer")
    void doesNotDuplicateTheOffer() {
        windowIsOpenNow();
        service.sync();
        service.sync();

        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("An offer the operator started by hand is never closed by the scheduler")
    void leavesAManualOfferAlone() {
        Promotion manual = running("MANUAL");
        Instant endsAt = manual.getEndsAt();
        windowIsClosedNow();

        service.sync();

        assertThat(manual.getEndsAt()).isEqualTo(endsAt);
    }

    @Test
    @DisplayName("Discounts never stack on top of one the operator is already running")
    void willNotStackDiscounts() {
        running("MANUAL");
        windowIsOpenNow();

        Map<String, Object> result = service.sync();

        assertThat(result).containsEntry("offerRunning", false);
        assertThat(result.get("skipped")).asString().contains("by hand");
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("Switched off, a running offer of ours is closed rather than left up")
    void closesOnBeingSwitchedOff() {
        windowIsOpenNow();
        service.sync();
        settings.setEnabled(false);

        service.sync();

        assertThat(stored.get(0).getEndsAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("Too little traffic means no window is suggested, rather than a guess")
    void refusesToGuessFromThinData() {
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(
                List.of(bucket(LocalDate.now().minusDays(1), 2, 500_000_000L)));

        OffPeakService.DayShape shape = service.analyse();

        assertThat(shape.suggestedStart()).isNull();
        assertThat(shape.note()).contains("day(s) of traffic recorded");
    }

    @Test
    @DisplayName("Given a fortnight of traffic it picks the empty hours, not the busy ones")
    void findsTheGenuinelyQuietHours() {
        List<TrafficUsage> rows = new ArrayList<>();
        for (int day = 1; day <= 14; day++) {
            LocalDate date = LocalDate.now().minusDays(day);
            for (int hour = 0; hour < 24; hour++) {
                // Busy evening, dead between 01:00 and 05:00.
                long mb = (hour >= 1 && hour <= 4) ? 5 : (hour >= 18 && hour <= 22 ? 4000 : 800);
                rows.add(bucket(date, hour, mb * 1_048_576L));
            }
        }
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(rows);

        OffPeakService.DayShape shape = service.analyse();

        assertThat(shape.suggestedStart()).isNotNull();
        // The suggested window must sit inside the dead stretch, and must not
        // reach into the evening peak.
        for (int h = 0; h < 24; h++) {
            boolean suggested = OffPeakService.inWindow(h, shape.suggestedStart(), shape.suggestedEnd());
            if (suggested) {
                assertThat(h).isBetween(1, 5);
            }
        }
        assertThat(OffPeakService.inWindow(20, shape.suggestedStart(), shape.suggestedEnd())).isFalse();
    }

    @Test
    @DisplayName("Hours with nothing recorded are never chosen as the quiet ones")
    void willNotDiscountAnUnrecordedHour() {
        List<TrafficUsage> rows = new ArrayList<>();
        for (int day = 1; day <= 14; day++) {
            LocalDate date = LocalDate.now().minusDays(day);
            for (int hour = 0; hour < 24; hour++) {
                // Capture is missing entirely between 08:00 and 13:00 — the
                // busiest selling hours of the morning, and the cheapest
                // possible mistake to make.
                if (hour >= 8 && hour <= 13) {
                    continue;
                }
                rows.add(bucket(date, hour, 900L * 1_048_576L));
            }
        }
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(rows);

        OffPeakService.DayShape shape = service.analyse();

        if (shape.suggestedStart() != null) {
            for (int h = 8; h <= 13; h++) {
                assertThat(OffPeakService.inWindow(h, shape.suggestedStart(), shape.suggestedEnd()))
                        .as("hour %d was never recorded and must not be discounted", h)
                        .isFalse();
            }
        }
        assertThat(shape.hours().get(9).get("observed")).isEqualTo(false);
        assertThat(shape.hours().get(3).get("observed")).isEqualTo(true);
    }

    @Test
    @DisplayName("When capture is too patchy to trust, no window is suggested at all")
    void declinesWhenCaptureIsPatchy() {
        List<TrafficUsage> rows = new ArrayList<>();
        for (int day = 1; day <= 14; day++) {
            // Only three hours a day were ever recorded: nowhere is a window.
            for (int hour : new int[]{0, 12, 18}) {
                rows.add(bucket(LocalDate.now().minusDays(day), hour, 900L * 1_048_576L));
            }
        }
        when(traffic.findByBucketHourGreaterThanEqual(any())).thenReturn(rows);

        OffPeakService.DayShape shape = service.analyse();

        assertThat(shape.suggestedStart()).isNull();
        assertThat(shape.note()).contains("gap in capture");
    }

    @Test
    @DisplayName("A window that crosses midnight is understood as one stretch")
    void handlesMidnight() {
        assertThat(OffPeakService.inWindow(23, 22, 6)).isTrue();
        assertThat(OffPeakService.inWindow(2, 22, 6)).isTrue();
        assertThat(OffPeakService.inWindow(6, 22, 6)).isFalse();
        assertThat(OffPeakService.inWindow(12, 22, 6)).isFalse();
        // Start equal to end is no window at all, not a 24-hour discount.
        assertThat(OffPeakService.inWindow(3, 5, 5)).isFalse();
    }

    @Test
    @DisplayName("Customers told recently are skipped, and the run is capped")
    void doesNotPesterTheSameCustomers() {
        windowIsOpenNow();
        settings.setNotify(true);
        settings.setMaxMessagesPerRun(2);
        when(audiences.forSegment(anyString())).thenReturn(List.of(
                new AudienceService.Recipient("254700000001", "A", Map.of()),
                new AudienceService.Recipient("254700000002", "B", Map.of()),
                new AudienceService.Recipient("254700000003", "C", Map.of())));
        when(notices.findByKindAndSentAtAfter(anyString(), any())).thenReturn(List.of(
                OfferNotice.builder().phoneNumber("254700000001").kind(OfferNotice.KIND_OFFPEAK)
                        .sentAt(Instant.now().minus(Duration.ofDays(1))).build()));

        assertThat(service.sync()).containsEntry("notified", 2);

        verify(smsService, never()).trySend(org.mockito.ArgumentMatchers.eq("254700000001"),
                anyString(), anyString(), anyString(), anyString());
        verify(smsService).trySend(org.mockito.ArgumentMatchers.eq("254700000002"),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Nobody is messaged twice in one night however often the hour ticks")
    void messagesOncePerNight() {
        windowIsOpenNow();
        settings.setNotify(true);
        when(audiences.forSegment(anyString())).thenReturn(List.of(
                new AudienceService.Recipient("254700000001", "A", Map.of())));

        assertThat(service.sync()).containsEntry("notified", 1);
        assertThat(service.sync()).containsEntry("notified", 0);
    }

    private static TrafficUsage bucket(LocalDate date, int hour, long bytes) {
        return TrafficUsage.builder()
                .bucketHour(LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(ZONE).toInstant())
                .routerId(1L)
                .userKey("u")
                .bytesDown(bytes)
                .bytesUp(0)
                .build();
    }
}
