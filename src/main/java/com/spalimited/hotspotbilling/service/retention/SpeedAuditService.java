package com.spalimited.hotspotbilling.service.retention;

import com.spalimited.hotspotbilling.domain.DeliveredSpeed;
import com.spalimited.hotspotbilling.domain.RadiusSession;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.DeliveredSpeedRepository;
import com.spalimited.hotspotbilling.repository.RadiusSessionRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.radius.RadiusAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that customers get the speed they pay for.
 *
 * <p>Built on the accounting the routers already send rather than on a speed
 * test, which matters: a speed test measures a moment the customer chose, on a
 * device they chose, and proves nothing about the rest of the month. This
 * measures every session, continuously, for everybody.
 *
 * <p>It has one honest limitation, stated here rather than buried: it can only
 * see what a customer <em>asked</em> for. Somebody who never pulls more than
 * 1 Mbps on a 10 Mbps line is not evidence of a fault, and the report says so
 * by refusing to call it a shortfall.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpeedAuditService {

    /** Sessions shorter than this are ignored — the rate is mostly noise. */
    private static final long MIN_SESSION_SECONDS = 120;

    private final RadiusSessionRepository sessions;
    private final SubscriberRepository subscribers;
    private final DeliveredSpeedRepository delivered;

    /**
     * Folds yesterday's sessions into one row per customer.
     *
     * <p>Runs on the finished day rather than the current one, so a session
     * still in progress cannot be measured half-way and recorded as slow.
     */
    @Transactional
    public int recordDay(LocalDate day) {
        Instant from = day.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        Map<Long, long[]> peaks = new LinkedHashMap<>();  // subscriberId -> {down, up, samples}
        for (RadiusSession session : sessions.findByStartedAtBetween(from, to)) {
            if (session.getSubscriberId() == null
                    || session.getSessionSeconds() < MIN_SESSION_SECONDS) {
                continue;
            }
            long seconds = session.getSessionSeconds();
            // Down is what the NAS sent to the customer, up is what it received
            // — the reverse of how it reads from the customer's chair, which is
            // the mistake that makes every report exactly backwards.
            long downBps = session.getOutOctets() * 8 / seconds;
            long upBps = session.getInOctets() * 8 / seconds;

            long[] row = peaks.computeIfAbsent(session.getSubscriberId(), k -> new long[3]);
            row[0] = Math.max(row[0], downBps);
            row[1] = Math.max(row[1], upBps);
            row[2]++;
        }

        int written = 0;
        for (Map.Entry<Long, long[]> entry : peaks.entrySet()) {
            Subscriber sub = subscribers.findById(entry.getKey()).orElse(null);
            if (sub == null) {
                continue;
            }
            long[] rates = RadiusAuthService.parseRate(sub.getBandwidth());

            DeliveredSpeed row = delivered
                    .findBySubscriberIdAndObservedOn(sub.getId(), day)
                    .orElseGet(() -> DeliveredSpeed.builder()
                            .subscriberId(sub.getId()).observedOn(day).build());
            row.setPeakDownBps(entry.getValue()[0]);
            row.setPeakUpBps(entry.getValue()[1]);
            row.setSamples((int) entry.getValue()[2]);
            // Stamped from the plan as it stands today, and never rewritten:
            // last month's shortfall must not be recomputed against this
            // month's price list.
            if (rates != null) {
                row.setPlanUpBps(rates[0]);
                row.setPlanDownBps(rates[1]);
            }
            delivered.save(row);
            written++;
        }
        log.info("Speed audit: recorded {} customer-days for {}", written, day);
        return written;
    }

    /** Customers who have been short of their plan on several recent days. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> shortfalls(int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(1, days));
        Map<Long, List<DeliveredSpeed>> byCustomer = new LinkedHashMap<>();
        for (DeliveredSpeed row : delivered.findByObservedOnGreaterThanEqualOrderByObservedOnDesc(from)) {
            byCustomer.computeIfAbsent(row.getSubscriberId(), k -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, List<DeliveredSpeed>> entry : byCustomer.entrySet()) {
            List<DeliveredSpeed> rows = entry.getValue();
            long bad = rows.stream().filter(DeliveredSpeed::isShortfall).count();
            // Three bad days out of the window. One is a busy evening on the
            // uplink; three is something wrong with this customer's line.
            if (bad < 3) {
                continue;
            }
            Subscriber sub = subscribers.findById(entry.getKey()).orElse(null);
            if (sub == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subscriberId", sub.getId());
            row.put("name", sub.getFullName());
            row.put("phoneNumber", sub.getPhoneNumber());
            row.put("plan", sub.getBandwidth());
            row.put("badDays", bad);
            row.put("daysMeasured", rows.size());
            row.put("worstPercent", rows.stream()
                    .map(DeliveredSpeed::getDeliveredPercent)
                    .filter(java.util.Objects::nonNull)
                    .min(Integer::compareTo).orElse(null));
            row.put("bestBpsSeen", rows.stream().mapToLong(DeliveredSpeed::getPeakDownBps).max().orElse(0));
            out.add(row);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("badDays"), (Long) a.get("badDays")));
        return out;
    }

    /** One customer's recent days, for the line on their own page. */
    @Transactional(readOnly = true)
    public List<DeliveredSpeed> history(Long subscriberId, int days) {
        return delivered.findBySubscriberIdAndObservedOnGreaterThanEqualOrderByObservedOnDesc(
                subscriberId, LocalDate.now().minusDays(Math.max(1, days)));
    }

    /** How long the window of evidence is, in words, for the report header. */
    static String windowLabel(int days) {
        return days >= 28 ? "the last month"
                : days >= 7 ? "the last " + (days / 7) + " week(s)"
                        : "the last " + days + " days";
    }
}
