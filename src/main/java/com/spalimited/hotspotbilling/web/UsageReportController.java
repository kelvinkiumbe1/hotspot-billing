package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.SubscriberUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is using what, across the whole network.
 *
 * <p>Subscriber traffic only. The hotspot side already has its own reports under
 * /analytics/traffic built on the hourly table, and merging the two would mean
 * one number that is a voucher code for half its rows and a customer for the
 * other half.
 */
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
// Same permission as the customers themselves. Usage is a support question
// more often than a finance one, and splitting the page across two
// permissions would mean a support agent seeing half of it.
@PreAuthorize("hasAuthority('CUSTOMERS')")
public class UsageReportController {

    private static final long MB = 1024L * 1024L;

    private final SubscriberUsageService usage;
    private final SubscriberRepository subscribers;

    /** Daily totals across every subscriber. */
    @GetMapping("/network")
    public Map<String, Object> network(@RequestParam(defaultValue = "30") int days) {
        LocalDate to = usage.today();
        LocalDate from = to.minusDays(Math.max(1, Math.min(400, days)) - 1L);
        List<Map<String, Object>> series = usage.networkSeries(from, to);
        long totalMb = series.stream().mapToLong(r -> (Long) r.get("totalMb")).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("series", series);
        out.put("totalMb", totalMb);
        return out;
    }

    /**
     * Heaviest customers over a range, names attached.
     *
     * <p>The range defaults to the current cap period rather than a rolling
     * thirty days, because the question this answers is almost always "who is
     * eating this month" and a rolling window silently straddles two of them.
     */
    @GetMapping("/top")
    public Map<String, Object> top(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int limit) {

        LocalDate end = to != null ? to : usage.today();
        LocalDate start = from != null ? from : usage.cycleStart(end);

        Map<Long, Subscriber> byId = new LinkedHashMap<>();
        for (Subscriber s : subscribers.findAll()) {
            byId.put(s.getId(), s);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (long[] t : usage.totalsBySubscriber(start, end)) {
            if (rows.size() >= Math.max(1, Math.min(500, limit))) {
                break;
            }
            Subscriber s = byId.get(t[0]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subscriberId", t[0]);
            // A customer deleted since the traffic was recorded still has rows.
            // Showing the id rather than dropping them keeps the total honest.
            row.put("name", s != null ? s.getFullName() : "Deleted customer #" + t[0]);
            row.put("pppoeUsername", s != null ? s.getPppoeUsername() : null);
            row.put("upMb", t[1] / MB);
            row.put("downMb", t[2] / MB);
            row.put("totalMb", (t[1] + t[2]) / MB);
            row.put("capMb", s != null ? s.getDataCapMb() : null);
            row.put("throttled", s != null && s.getFupAppliedAt() != null);
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("rows", rows);
        return out;
    }
}
