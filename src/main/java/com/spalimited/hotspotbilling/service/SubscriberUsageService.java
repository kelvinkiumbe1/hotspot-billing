package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriberUsageDaily;
import com.spalimited.hotspotbilling.repository.SubscriberUsageDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each fibre/PPPoE customer has used, kept day by day.
 *
 * <p>Before this, a subscriber's usage was one running total on the row that got
 * zeroed on the 1st of the month with nothing archived. The bytes were already
 * being counted -- RADIUS accounting has had them per session all along -- they
 * just had nowhere to go. This is that somewhere.
 *
 * <p>Everything is stored in bytes and bucketed in the operator's own timezone.
 * The timezone matters more than it looks: a customer in Nairobi asking what they
 * used yesterday means yesterday in Nairobi, and bucketing on UTC would push
 * three hours of every evening into the wrong day.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriberUsageService {

    private static final long MB_BYTES = 1024L * 1024L;

    /** Roughly thirteen months: this month, plus a full year to compare against. */
    private static final int KEEP_DAYS = 400;

    /**
     * The server's timezone, which the rest of this codebase already treats as
     * the operator's -- see AgentPayoutService and AiInsightsService. An ISP runs
     * in one country, so the day boundary that matters is the one the server is
     * set to. Kept as a constant here so that if a multi-country deployment ever
     * needs it configurable, there is one place to change.
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final SubscriberUsageDailyRepository usage;

    public LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** First day of the cap period the given day falls in. */
    public LocalDate cycleStart(LocalDate day) {
        return day.withDayOfMonth(1);
    }

    /**
     * Adds traffic to today's row for this subscriber.
     *
     * <p>In its own transaction, and tolerant of losing an insert race. Two
     * accounting packets for the same customer can land on two threads at the
     * same moment, both find no row for today and both insert; the unique
     * constraint means one of them loses. Losing that race must not roll back the
     * accounting update that triggered it, which is the whole reason for
     * REQUIRES_NEW here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long subscriberId, long bytesUp, long bytesDown) {
        if (subscriberId == null || (bytesUp <= 0 && bytesDown <= 0)) {
            return;
        }
        LocalDate day = today();
        try {
            add(subscriberId, day, bytesUp, bytesDown);
        } catch (DataIntegrityViolationException raceLost) {
            // The other thread created the row. Read it back and add to it.
            try {
                add(subscriberId, day, bytesUp, bytesDown);
            } catch (Exception stillFailing) {
                log.warn("Could not record usage for subscriber {}: {}",
                        subscriberId, stillFailing.getMessage());
            }
        }
    }

    private void add(Long subscriberId, LocalDate day, long up, long down) {
        SubscriberUsageDaily row = usage.findBySubscriberIdAndDay(subscriberId, day)
                .orElseGet(() -> SubscriberUsageDaily.builder()
                        .subscriberId(subscriberId).day(day).bytesUp(0).bytesDown(0).build());
        row.setBytesUp(row.getBytesUp() + Math.max(0, up));
        row.setBytesDown(row.getBytesDown() + Math.max(0, down));
        usage.saveAndFlush(row);
    }

    /** This customer's total so far in the current cap period, in bytes. */
    @Transactional(readOnly = true)
    public long thisCycleBytes(Long subscriberId) {
        LocalDate today = today();
        return usage.totalBytes(subscriberId, cycleStart(today), today);
    }

    @Transactional(readOnly = true)
    public long bytesBetween(Long subscriberId, LocalDate from, LocalDate to) {
        return usage.totalBytes(subscriberId, from, to);
    }

    /**
     * A customer's daily usage over the last N days, with the quiet days filled
     * in as zero.
     *
     * <p>The gaps matter. A chart drawn straight from the stored rows silently
     * closes them up, so a line that was down for a week looks like a week of
     * ordinary use.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dailySeries(Long subscriberId, int days) {
        LocalDate to = today();
        LocalDate from = to.minusDays(Math.max(1, days) - 1L);
        Map<LocalDate, SubscriberUsageDaily> found = new LinkedHashMap<>();
        for (SubscriberUsageDaily r
                : usage.findBySubscriberIdAndDayBetweenOrderByDayAsc(subscriberId, from, to)) {
            found.put(r.getDay(), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            SubscriberUsageDaily r = found.get(d);
            out.add(Map.of(
                    "day", d.toString(),
                    "bytesUp", r == null ? 0L : r.getBytesUp(),
                    "bytesDown", r == null ? 0L : r.getBytesDown(),
                    "totalMb", r == null ? 0L : r.getTotalBytes() / MB_BYTES));
        }
        return out;
    }

    /** Whole-network daily totals, gaps included as zero. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> networkSeries(LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> found = new LinkedHashMap<>();
        for (Object[] row : usage.dailyTotals(from, to)) {
            found.put((LocalDate) row[0],
                    new long[] { ((Number) row[1]).longValue(), ((Number) row[2]).longValue() });
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long[] v = found.getOrDefault(d, new long[] { 0, 0 });
            out.add(Map.of("day", d.toString(), "bytesUp", v[0], "bytesDown", v[1],
                    "totalMb", (v[0] + v[1]) / MB_BYTES));
        }
        return out;
    }

    /** Totals per subscriber over a range, heaviest first: [id, up, down]. */
    @Transactional(readOnly = true)
    public List<long[]> totalsBySubscriber(LocalDate from, LocalDate to) {
        List<long[]> out = new ArrayList<>();
        for (Object[] row : usage.totalsBySubscriber(from, to)) {
            out.add(new long[] {
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue() });
        }
        return out;
    }

    /** How far through their allowance a customer is, or null if they have none. */
    @Transactional(readOnly = true)
    public Map<String, Object> capStatus(Subscriber sub) {
        if (sub.getDataCapMb() == null || sub.getDataCapMb() <= 0) {
            return null;
        }
        long used = thisCycleBytes(sub.getId());
        long capBytes = sub.getDataCapMb() * MB_BYTES;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capMb", sub.getDataCapMb());
        out.put("usedMb", used / MB_BYTES);
        out.put("remainingMb", Math.max(0, (capBytes - used) / MB_BYTES));
        out.put("percent", capBytes == 0 ? 0 : (int) Math.min(100, used * 100 / capBytes));
        out.put("over", used >= capBytes);
        out.put("action", sub.getFupAction() == null ? "NOTIFY" : sub.getFupAction().name());
        out.put("appliedAt", sub.getFupAppliedAt());
        out.put("cycleStart", cycleStart(today()).toString());
        return out;
    }

    /**
     * Drops usage older than the retention window.
     *
     * <p>Monthly rather than nightly. This table grows by one row per customer per
     * day, so on a two-thousand-line network it is under a million rows a year and
     * there is nothing to be gained by checking it every night.
     */
    @Transactional
    public long prune() {
        long removed = usage.deleteByDayBefore(today().minusDays(KEEP_DAYS));
        if (removed > 0) {
            log.info("Pruned {} subscriber usage row(s) older than {} days", removed, KEEP_DAYS);
        }
        return removed;
    }
}
