package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.OfferNotice;
import com.spalimited.hotspotbilling.domain.OffpeakSettings;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Promotion;
import com.spalimited.hotspotbilling.domain.TrafficUsage;
import com.spalimited.hotspotbilling.repository.OfferNoticeRepository;
import com.spalimited.hotspotbilling.repository.OffpeakSettingsRepository;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PromotionRepository;
import com.spalimited.hotspotbilling.repository.TrafficUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Selling the hours the link is already paid for.
 *
 * <p>An ISP's biggest cost does not care whether anybody is online. Between
 * about ten at night and six in the morning most of the capacity goes to
 * waste, and a customer who would happily pay eighty shillings for the night
 * is never asked. The traffic needed to know which hours those actually are
 * has been recorded all along and never used for anything but reports.
 *
 * <p>So: work the quiet window out from the data, discount across it, and tell
 * the people most likely to take it — once a week at most, because the fastest
 * way to ruin a good offer is to send it every night.
 *
 * <p>The scheduler only ever closes promotions it opened itself. A weekend
 * sale the operator started by hand is theirs, and must survive the moment
 * this decides the quiet hours are over.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OffPeakService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Shortest and longest window worth calling "off-peak". */
    private static final int MIN_WINDOW_HOURS = 3;
    private static final int MAX_WINDOW_HOURS = 10;

    /** Below this many distinct days of traffic, the data is not worth trusting. */
    private static final int MIN_DAYS_OF_DATA = 7;

    private final OffpeakSettingsRepository settingsRepo;
    private final TrafficUsageRepository traffic;
    private final PaymentRepository payments;
    private final PromotionRepository promotions;
    private final OfferNoticeRepository notices;
    private final AudienceService audiences;
    private final SmsService smsService;
    private final PortalSettingsService portalSettings;

    // --- Settings ---

    @Transactional
    public OffpeakSettings settings() {
        return settingsRepo.findById(OffpeakSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(OffpeakSettings.builder()
                        .id(OffpeakSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public OffpeakSettings update(OffpeakSettings in) {
        OffpeakSettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setAutoWindow(in.isAutoWindow());
        s.setLookbackDays(clamp(in.getLookbackDays(), 7, 90));
        s.setWindowStartHour(clamp(in.getWindowStartHour(), 0, 23));
        s.setWindowEndHour(clamp(in.getWindowEndHour(), 0, 23));
        s.setDiscountPercent(clamp(in.getDiscountPercent(), 1, 90));
        s.setNotify(in.isNotify());
        s.setAudience(in.getAudience() == null || in.getAudience().isBlank()
                ? "expired_hotspot_users" : in.getAudience());
        s.setMaxMessagesPerRun(clamp(in.getMaxMessagesPerRun(), 1, 5000));
        s.setMinDaysBetweenMessages(clamp(in.getMinDaysBetweenMessages(), 1, 90));
        return settingsRepo.save(s);
    }

    // --- Reading the day ---

    /**
     * An hour must have carried traffic on at least this share of the observed
     * days before its quietness means anything. Traffic capture is hotspot-only
     * and depends on the router being reachable, so an hour with no rows at all
     * is far more likely to be a gap in the recording than a genuinely idle
     * hour — and discounting the morning because nothing was captured then
     * would give away the busiest sales of the day.
     */
    private static final double MIN_COVERAGE = 0.34;

    /** Traffic and sales by hour of day, and the window they suggest. */
    public record DayShape(List<Map<String, Object>> hours, Integer suggestedStart,
                           Integer suggestedEnd, int daysOfData, String note) {
    }

    /**
     * What the last few weeks look like hour by hour. Traffic says where the
     * spare capacity is; sales say where the demand is not. The window worth
     * discounting is where both are low — idle capacity nobody is buying.
     */
    @Transactional(readOnly = true)
    public DayShape analyse() {
        OffpeakSettings s = settings();
        Instant since = Instant.now().minus(Duration.ofDays(s.getLookbackDays()));

        long[] bytes = new long[24];
        long[] sales = new long[24];
        Set<LocalDate> days = new HashSet<>();
        // Which days each hour was actually recorded on, so a gap in capture
        // is not mistaken for a quiet hour.
        List<Set<LocalDate>> daysPerHour = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            daysPerHour.add(new HashSet<>());
        }

        for (TrafficUsage row : traffic.findByBucketHourGreaterThanEqual(since)) {
            LocalDateTime at = LocalDateTime.ofInstant(row.getBucketHour(), ZONE);
            bytes[at.getHour()] += row.getBytesUp() + row.getBytesDown();
            days.add(at.toLocalDate());
            daysPerHour.get(at.getHour()).add(at.toLocalDate());
        }
        for (Payment p : payments.findByStatusAndCreatedAtAfter(Payment.Status.SUCCESS, since)) {
            Instant at = p.getCompletedAt() != null ? p.getCompletedAt() : p.getCreatedAt();
            if (at != null) {
                sales[LocalDateTime.ofInstant(at, ZONE).getHour()]++;
            }
        }

        int needed = Math.max(1, (int) Math.ceil(days.size() * MIN_COVERAGE));
        boolean[] observed = new boolean[24];
        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            observed[h] = daysPerHour.get(h).size() >= needed;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour", h);
            row.put("megabytes", bytes[h] / 1_048_576L);
            row.put("sales", sales[h]);
            row.put("observed", observed[h]);
            hours.add(row);
        }

        if (days.size() < MIN_DAYS_OF_DATA) {
            return new DayShape(hours, null, null, days.size(),
                    "Only " + days.size() + " day(s) of traffic recorded so far — at least "
                            + MIN_DAYS_OF_DATA + " are needed before the quiet hours mean anything. "
                            + "Set the window by hand until then.");
        }

        int[] window = quietestWindow(bytes, sales, observed);
        if (window == null) {
            long gaps = countFalse(observed);
            return new DayShape(hours, null, null, days.size(),
                    gaps + " hour(s) of the day have almost no traffic recorded at all. That is more "
                            + "likely a gap in capture than a quiet hour, so no window is being "
                            + "suggested — set it by hand.");
        }
        return new DayShape(hours, window[0], window[1], days.size(), null);
    }

    private static long countFalse(boolean[] flags) {
        long n = 0;
        for (boolean f : flags) {
            if (!f) {
                n++;
            }
        }
        return n;
    }

    /**
     * The contiguous run of hours — wrapping past midnight, as the quiet hours
     * always do — with the lowest combined traffic and sales. Scored on both
     * so a busy-but-unsold hour is not mistaken for spare capacity, and each
     * measure is normalised against its own peak so bytes and sale counts can
     * be added without one drowning the other.
     *
     * <p>Hours with no traffic recorded are not candidates at any price.
     * Nothing looks quieter than an hour that was never measured, and the
     * cheapest way to lose a morning's revenue is to discount it.
     *
     * <p>Returns null when no window avoids the unrecorded hours.
     */
    private static int[] quietestWindow(long[] bytes, long[] sales, boolean[] observed) {
        double peakBytes = Math.max(1, max(bytes));
        double peakSales = Math.max(1, max(sales));

        double bestScore = Double.MAX_VALUE;
        int bestStart = -1;
        int bestLength = MIN_WINDOW_HOURS;

        for (int start = 0; start < 24; start++) {
            double sum = 0;
            for (int length = 1; length <= MAX_WINDOW_HOURS; length++) {
                int hour = (start + length - 1) % 24;
                if (!observed[hour]) {
                    break; // and every longer window from this start too
                }
                sum += bytes[hour] / peakBytes + sales[hour] / peakSales;
                if (length < MIN_WINDOW_HOURS) {
                    continue;
                }
                double mean = sum / length;
                // A longer window at practically the same quietness is worth
                // more: it is more hours of otherwise wasted capacity sold.
                boolean clearlyBetter = bestStart < 0 || mean < bestScore * 0.98;
                boolean tiedButLonger = bestStart >= 0 && mean < bestScore * 1.02 && length > bestLength;
                if (clearlyBetter || tiedButLonger) {
                    bestStart = start;
                    bestLength = length;
                    // Keep the tighter bound when we stretched on a tie, so a
                    // run of 2% concessions cannot walk the window into a busy
                    // hour one step at a time.
                    bestScore = Math.min(mean, bestScore);
                }
            }
        }
        return bestStart < 0 ? null : new int[]{bestStart, (bestStart + bestLength) % 24};
    }

    private static long max(long[] values) {
        long m = 0;
        for (long v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    // --- Running the offer ---

    /**
     * Called every hour. Opens the discount when the quiet window starts,
     * closes it when the window ends, and tells the audience once.
     */
    @Transactional
    public Map<String, Object> sync() {
        OffpeakSettings s = settings();
        Map<String, Object> out = new LinkedHashMap<>();
        int[] window = activeWindow(s);
        boolean inWindow = window != null && inWindow(LocalTime.now(ZONE).getHour(), window[0], window[1]);
        Optional<Promotion> ours = runningOffpeak();

        out.put("inWindow", inWindow);
        out.put("windowStart", window == null ? null : window[0]);
        out.put("windowEnd", window == null ? null : window[1]);

        if (!s.isEnabled() || !inWindow) {
            // Only ever close what this opened. A sale the operator started by
            // hand is theirs and outlives our opinion about the quiet hours.
            ours.ifPresent(p -> {
                p.setEndsAt(Instant.now());
                promotions.save(p);
                log.info("Off-peak offer closed");
            });
            out.put("offerRunning", false);
            out.put("closed", ours.isPresent());
            return out;
        }

        if (ours.isEmpty()) {
            if (manualPromotionRunning()) {
                // Stacking discounts is how a KES 50 pass ends up at KES 12.
                out.put("offerRunning", false);
                out.put("skipped", "an offer started by hand is already running");
                return out;
            }
            Promotion promo = promotions.save(Promotion.builder()
                    .title("Night rate — " + s.getDiscountPercent() + "% off")
                    .discountPercent(s.getDiscountPercent())
                    .startsAt(Instant.now())
                    .endsAt(nextOccurrenceOf(window[1]))
                    .source(Promotion.SOURCE_OFFPEAK)
                    .build());
            log.info("Off-peak offer opened: {}% off until {}", promo.getDiscountPercent(), promo.getEndsAt());
            out.put("opened", true);
        }
        out.put("offerRunning", true);
        out.put("notified", s.isNotify() ? notifyAudience(s, window) : 0);
        return out;
    }

    /** The window in force: worked out from the data, or set by hand. */
    private int[] activeWindow(OffpeakSettings s) {
        if (s.isAutoWindow()) {
            DayShape shape = analyse();
            if (shape.suggestedStart() != null) {
                return new int[]{shape.suggestedStart(), shape.suggestedEnd()};
            }
            // Not enough history yet: fall back to the configured hours rather
            // than discounting at a time nobody chose.
        }
        return s.getWindowStartHour() == s.getWindowEndHour()
                ? null
                : new int[]{s.getWindowStartHour(), s.getWindowEndHour()};
    }

    private Optional<Promotion> runningOffpeak() {
        return promotions.findTop20ByOrderByCreatedAtDesc().stream()
                .filter(p -> Promotion.SOURCE_OFFPEAK.equals(p.getSource()))
                .filter(OffPeakService::liveNow)
                .findFirst();
    }

    private boolean manualPromotionRunning() {
        return promotions.findTop20ByOrderByCreatedAtDesc().stream()
                .filter(p -> !Promotion.SOURCE_OFFPEAK.equals(p.getSource()))
                .anyMatch(OffPeakService::liveNow);
    }

    /**
     * Start is inclusive. An offer opened on this very tick of the clock is
     * running — treating it as not-yet-started is how the same sync opens a
     * second one a moment later and the customer gets two stacked discounts.
     */
    private static boolean liveNow(Promotion p) {
        Instant now = Instant.now();
        return !p.getStartsAt().isAfter(now) && p.getEndsAt().isAfter(now);
    }

    /**
     * One message per window opening, to people who have not heard about this
     * recently. Capped per run, because a thousand messages at ten at night is
     * a bill and a complaint rather than a campaign.
     */
    private int notifyAudience(OffpeakSettings s, int[] window) {
        LocalDate today = LocalDate.now(ZONE);
        if (today.equals(s.getLastNotifiedOn())) {
            return 0;
        }
        Set<String> recentlyTold = new HashSet<>();
        Instant since = Instant.now().minus(Duration.ofDays(s.getMinDaysBetweenMessages()));
        notices.findByKindAndSentAtAfter(OfferNotice.KIND_OFFPEAK, since)
                .forEach(n -> recentlyTold.add(n.getPhoneNumber()));

        String business = portalSettings.settings().getBusinessName();
        String body = "🌙 " + (business == null || business.isBlank() ? "Night rate" : business + " night rate")
                + ": *" + s.getDiscountPercent() + "% off* every WiFi package until "
                + String.format("%02d:00", window[1]) + ".\n"
                + "Reply to this message to buy at the night price.";

        int sent = 0;
        for (AudienceService.Recipient r : audiences.forSegment(s.getAudience())) {
            if (sent >= s.getMaxMessagesPerRun()) {
                break;
            }
            String phone = audiences.normalise(r.phone());
            if (phone == null || phone.isBlank() || recentlyTold.contains(phone)) {
                continue;
            }
            smsService.trySend(phone, body, r.name(), "offpeak", "system");
            notices.save(OfferNotice.builder()
                    .phoneNumber(phone)
                    .kind(OfferNotice.KIND_OFFPEAK)
                    .build());
            recentlyTold.add(phone);
            sent++;
        }
        s.setLastNotifiedOn(today);
        settingsRepo.save(s);
        if (sent > 0) {
            log.info("Off-peak offer sent to {} customer(s)", sent);
        }
        return sent;
    }

    /** Handles a window that crosses midnight, which the quiet one always does. */
    static boolean inWindow(int hour, int start, int end) {
        if (start == end) {
            return false;
        }
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    /** The next time the clock reads this hour, today or tomorrow. */
    private static Instant nextOccurrenceOf(int hour) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime at = now.toLocalDate().atTime(hour, 0);
        if (!at.isAfter(now)) {
            at = at.plusDays(1);
        }
        return at.atZone(ZONE).toInstant();
    }

    /** What the offer would do to a price right now, for the settings screen. */
    @Transactional(readOnly = true)
    public BigDecimal exampleAt(BigDecimal price) {
        OffpeakSettings s = settings();
        return price.multiply(BigDecimal.valueOf(100 - s.getDiscountPercent()))
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                .max(BigDecimal.ONE);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
