package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CapacitySettings;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.TrafficUsage;
import com.spalimited.hotspotbilling.repository.CapacitySettingsRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.TrafficUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capacity planning from the traffic already on disk.
 *
 * <p>Capacity is the one thing an operator cannot fix in an afternoon.
 * Backhaul is ordered weeks ahead, and the signal that it is needed — the busy
 * hour creeping up week after week — is invisible in a dashboard that shows
 * only right now. By the time the evenings start crawling and customers ring,
 * the fix is a month away.
 *
 * <p>So each site's busy hour is measured, compared against what its link can
 * carry, and projected forward. The busy hour is the 95th percentile of hourly
 * throughput rather than the maximum: one router reboot pushing a month's
 * worth of catch-up traffic through a single hour should not be read as the
 * site being full.
 *
 * <p>Advisory on purpose. It recommends; it changes nothing. Buying backhaul
 * is not a decision to automate, and neither is telling a customer they should
 * be on a dearer package.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapacityService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Below this the busy hour is an anecdote, not a measurement. */
    private static final int MIN_DAYS_FOR_BUSY_HOUR = 7;

    /** And a trend needs enough weeks to have a direction at all. */
    private static final int MIN_DAYS_FOR_TREND = 21;

    /** Beyond this far out the projection is arithmetic, not a forecast. */
    private static final int MAX_PROJECTION_WEEKS = 52;

    private final CapacitySettingsRepository settingsRepo;
    private final TrafficUsageRepository traffic;
    private final RouterRepository routers;
    private final SmsService smsService;
    private final MessagingSettingsService messagingSettings;

    // --- Settings ---

    @Transactional
    public CapacitySettings settings() {
        return settingsRepo.findById(CapacitySettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(CapacitySettings.builder()
                        .id(CapacitySettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public CapacitySettings update(CapacitySettings in) {
        CapacitySettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setLookbackDays(clamp(in.getLookbackDays(), 7, 180));
        s.setWarnPercent(clamp(in.getWarnPercent(), 10, 99));
        s.setCriticalPercent(clamp(Math.max(in.getCriticalPercent(), in.getWarnPercent() + 1), 11, 100));
        s.setUnderusedPercent(clamp(in.getUnderusedPercent(), 1, 90));
        s.setNotify(in.isNotify());
        s.setNotifyDayOfWeek(clamp(in.getNotifyDayOfWeek(), 1, 7));
        s.setNotifyHour(clamp(in.getNotifyHour(), 0, 23));
        return settingsRepo.save(s);
    }

    // --- The picture ---

    /** One site's busy hour against what its link can carry. */
    public record SiteOutlook(Long routerId, String name, String location, Integer capacityMbps,
                              double busyHourMbps, Integer usedPercent, Double weeklyGrowthMbps,
                              Integer weeksUntilFull, int daysOfData, String verdict, String advice) {
    }

    /** A customer using far more than their package suggests. */
    public record HeavyUser(String userKey, Long routerId, String routerName,
                            long gigabytes, double shareOfSitePercent) {
    }

    public record Outlook(List<SiteOutlook> sites, List<HeavyUser> heaviest,
                          int daysOfData, String note) {
    }

    @Transactional(readOnly = true)
    public Outlook outlook() {
        CapacitySettings s = settings();
        Instant since = Instant.now().minus(Duration.ofDays(s.getLookbackDays()));
        List<TrafficUsage> rows = traffic.findByBucketHourGreaterThanEqual(since);

        // routerId -> bucketHour -> bytes, so each site's hourly throughput can
        // be ranked. Users are tallied at the same time to save a second pass.
        Map<Long, Map<Instant, Long>> perSiteHour = new HashMap<>();
        Map<Long, Map<String, Long>> perSiteUser = new HashMap<>();
        Map<Long, Set<LocalDate>> daysSeen = new HashMap<>();
        Instant recentFrom = Instant.now().minus(Duration.ofDays(7));
        Map<Long, Map<Instant, Long>> recentHour = new HashMap<>();
        Map<Long, Map<Instant, Long>> earlierHour = new HashMap<>();
        Instant earlierUntil = since.plus(Duration.ofDays(7));

        for (TrafficUsage row : rows) {
            Long routerId = row.getRouterId();
            long bytes = row.getBytesUp() + row.getBytesDown();
            perSiteHour.computeIfAbsent(routerId, k -> new HashMap<>())
                    .merge(row.getBucketHour(), bytes, Long::sum);
            perSiteUser.computeIfAbsent(routerId, k -> new HashMap<>())
                    .merge(row.getUserKey() == null ? "(unknown)" : row.getUserKey(), bytes, Long::sum);
            daysSeen.computeIfAbsent(routerId, k -> new HashSet<>())
                    .add(row.getBucketHour().atZone(ZONE).toLocalDate());
            if (!row.getBucketHour().isBefore(recentFrom)) {
                recentHour.computeIfAbsent(routerId, k -> new HashMap<>())
                        .merge(row.getBucketHour(), bytes, Long::sum);
            } else if (row.getBucketHour().isBefore(earlierUntil)) {
                earlierHour.computeIfAbsent(routerId, k -> new HashMap<>())
                        .merge(row.getBucketHour(), bytes, Long::sum);
            }
        }

        List<SiteOutlook> sites = new ArrayList<>();
        for (Router router : routers.findByEnabledTrue()) {
            sites.add(assess(s, router,
                    perSiteHour.getOrDefault(router.getId(), Map.of()),
                    recentHour.getOrDefault(router.getId(), Map.of()),
                    earlierHour.getOrDefault(router.getId(), Map.of()),
                    daysSeen.getOrDefault(router.getId(), Set.of()).size()));
        }
        sites.sort(Comparator.comparing(
                (SiteOutlook o) -> o.usedPercent() == null ? -1 : o.usedPercent()).reversed());

        int totalDays = daysSeen.values().stream()
                .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()).size();
        String note = totalDays < MIN_DAYS_FOR_BUSY_HOUR
                ? "Only " + totalDays + " day(s) of traffic recorded. Busy-hour figures need at least "
                        + MIN_DAYS_FOR_BUSY_HOUR + " days, and a trend needs " + MIN_DAYS_FOR_TREND + "."
                : null;

        return new Outlook(sites, heaviest(perSiteUser), totalDays, note);
    }

    private SiteOutlook assess(CapacitySettings s, Router router, Map<Instant, Long> hourly,
                               Map<Instant, Long> recent, Map<Instant, Long> earlier, int days) {
        double busyMbps = percentileMbps(hourly.values(), 0.95);
        Integer capacity = router.getCapacityMbps();

        if (capacity == null || capacity <= 0) {
            return new SiteOutlook(router.getId(), router.getName(), router.getLocation(), null,
                    round(busyMbps), null, null, null, days, "UNKNOWN",
                    "Set what this link can carry and its busy hour of "
                            + round(busyMbps) + " Mbps becomes a percentage worth watching.");
        }
        if (days < MIN_DAYS_FOR_BUSY_HOUR) {
            return new SiteOutlook(router.getId(), router.getName(), router.getLocation(), capacity,
                    round(busyMbps), null, null, null, days, "UNKNOWN",
                    "Only " + days + " day(s) of traffic from this site so far.");
        }

        int usedPercent = (int) Math.round(busyMbps / capacity * 100);

        Double weeklyGrowth = null;
        Integer weeksLeft = null;
        if (days >= MIN_DAYS_FOR_TREND && !earlier.isEmpty() && !recent.isEmpty()) {
            double then = percentileMbps(earlier.values(), 0.95);
            double now = percentileMbps(recent.values(), 0.95);
            double weeks = Math.max(1, (s.getLookbackDays() - 7) / 7.0);
            double growth = (now - then) / weeks;
            weeklyGrowth = round(growth);
            if (growth > 0.01) {
                double ceiling = capacity * (s.getCriticalPercent() / 100.0);
                double left = (ceiling - now) / growth;
                if (left <= 0) {
                    weeksLeft = 0;
                } else if (left <= MAX_PROJECTION_WEEKS) {
                    weeksLeft = (int) Math.ceil(left);
                }
            }
        }

        String verdict;
        String advice;
        if (usedPercent >= s.getCriticalPercent()) {
            verdict = "CRITICAL";
            advice = "The busy hour is at " + usedPercent + "% of this link. Customers here are already "
                    + "slowing down in the evenings. More backhaul, or split the load onto another AP.";
        } else if (usedPercent >= s.getWarnPercent()) {
            verdict = "WARNING";
            advice = "At " + usedPercent + "% in the busy hour, this site has little room left."
                    + (weeksLeft == null ? "" : " On the current trend it fills in about "
                            + weeksLeft + " week(s).");
        } else if (weeksLeft != null && weeksLeft <= 8) {
            verdict = "WARNING";
            advice = "Comfortable today at " + usedPercent + "%, but growing about " + weeklyGrowth
                    + " Mbps a week — full in roughly " + weeksLeft + " week(s). Ordering backhaul "
                    + "takes longer than that.";
        } else if (usedPercent <= s.getUnderusedPercent()) {
            verdict = "UNDERUSED";
            advice = "Only " + usedPercent + "% of this link is being used in its busiest hour. "
                    + "You are paying for the rest — this is a site to sell into, not upgrade.";
        } else {
            verdict = "OK";
            advice = "Busy hour at " + usedPercent + "% of capacity"
                    + (weeksLeft == null ? "." : ", full in roughly " + weeksLeft + " week(s) at this rate.");
        }
        return new SiteOutlook(router.getId(), router.getName(), router.getLocation(), capacity,
                round(busyMbps), usedPercent, weeklyGrowth, weeksLeft, days, verdict, advice);
    }

    /**
     * The handful of users carrying a disproportionate share of a site. Worth
     * a conversation about a bigger package — or, occasionally, a look at
     * whether one voucher is being shared around a whole building.
     */
    private List<HeavyUser> heaviest(Map<Long, Map<String, Long>> perSiteUser) {
        Map<Long, String> names = new HashMap<>();
        routers.findAll().forEach(r -> names.put(r.getId(), r.getName()));

        List<HeavyUser> out = new ArrayList<>();
        perSiteUser.forEach((routerId, users) -> {
            long siteTotal = users.values().stream().mapToLong(Long::longValue).sum();
            if (siteTotal <= 0) {
                return;
            }
            users.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> out.add(new HeavyUser(
                            e.getKey(), routerId, names.getOrDefault(routerId, "#" + routerId),
                            e.getValue() / 1_073_741_824L,
                            round(e.getValue() * 100.0 / siteTotal))));
        });
        out.sort(Comparator.comparingLong(HeavyUser::gigabytes).reversed());
        return out.stream().limit(10).toList();
    }

    /**
     * The busy hour, as a percentile of hourly throughput. The maximum would
     * be one bad night — a router coming back after an outage and pushing a
     * queue of catch-up traffic through a single hour reads as the site being
     * full when it is nothing of the kind.
     */
    private static double percentileMbps(java.util.Collection<Long> hourlyBytes, double percentile) {
        if (hourlyBytes.isEmpty()) {
            return 0;
        }
        List<Long> sorted = hourlyBytes.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        long bytes = sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        // Bytes in an hour to megabits per second.
        return bytes * 8.0 / 3600.0 / 1_000_000.0;
    }

    // --- The weekly word ---

    /**
     * Once a week, if anything needs saying. Silence is the normal state and
     * should stay meaningful: a message every Monday saying everything is fine
     * teaches the operator to ignore Monday messages.
     */
    @Transactional
    public int maybeNotify() {
        CapacitySettings s = settings();
        if (!s.isEnabled() || !s.isNotify()) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZONE);
        if (today.equals(s.getLastNotifiedOn())
                || today.getDayOfWeek().getValue() != s.getNotifyDayOfWeek()
                || LocalTime.now(ZONE).getHour() != s.getNotifyHour()) {
            return 0;
        }

        Outlook outlook = outlook();
        List<SiteOutlook> pressing = outlook.sites().stream()
                .filter(o -> "CRITICAL".equals(o.verdict()) || "WARNING".equals(o.verdict()))
                .toList();
        s.setLastNotifiedOn(today);
        settingsRepo.save(s);
        if (pressing.isEmpty()) {
            return 0;
        }

        StringBuilder sb = new StringBuilder("📡 Capacity — ").append(pressing.size())
                .append(" site(s) need attention:\n");
        for (SiteOutlook o : pressing) {
            sb.append("• ").append(o.name()).append(": ").append(o.usedPercent())
                    .append("% in the busy hour");
            if (o.weeksUntilFull() != null) {
                sb.append(", full in ~").append(o.weeksUntilFull()).append("w");
            }
            sb.append('\n');
        }
        alertOperator(sb.toString());
        return pressing.size();
    }

    private void alertOperator(String message) {
        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, message);
        }
    }

    /** Records what a site's link can carry — the one figure nothing can measure. */
    @Transactional
    public Router setCapacity(Long routerId, Integer mbps) {
        Router router = routers.findById(routerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown router: " + routerId));
        router.setCapacityMbps(mbps == null || mbps <= 0 ? null : mbps);
        return routers.save(router);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
