package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.RetentionScore;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.retention.RetentionService;
import com.spalimited.hotspotbilling.service.retention.SpeedAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is about to leave, and who is not getting what they pay for.
 *
 * <p>Guarded by CUSTOMERS rather than ANALYTICS: this is a call list for the
 * person who rings customers, not a report for the person who reads charts.
 */
@RestController
@RequestMapping("/api/admin/retention")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CUSTOMERS')")
public class RetentionController {

    private final RetentionService retention;
    private final SpeedAuditService speedAudit;
    private final SubscriberRepository subscribers;
    private final AuditService audit;

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", retention.summary());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RetentionScore score : retention.atRisk()) {
            Subscriber sub = subscribers.findById(score.getSubscriberId()).orElse(null);
            if (sub == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subscriberId", sub.getId());
            row.put("name", sub.getFullName());
            row.put("phoneNumber", sub.getPhoneNumber());
            row.put("monthlyFee", sub.getMonthlyFee());
            row.put("paidUntil", sub.getPaidUntil());
            row.put("score", score.getScore());
            row.put("band", score.getBand().name());
            // Split back into a list so the screen can show them as separate
            // lines — one run-on sentence of five reasons gets skimmed and
            // skipped, which defeats the point of collecting them.
            row.put("reasons", score.getReasons() == null || score.getReasons().isBlank()
                    ? List.of() : List.of(score.getReasons().split(" · ")));
            row.put("action", score.getSuggestedAction());
            row.put("worsening", score.isWorsening());
            row.put("acknowledgedAt", score.getAcknowledgedAt());
            row.put("acknowledgedBy", score.getAcknowledgedBy());
            rows.add(row);
        }
        out.put("customers", rows);
        return out;
    }

    /** Marks one as dealt with, so the list stops accusing everyone of ignoring it. */
    @PostMapping("/{subscriberId}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable Long subscriberId, Principal principal) {
        RetentionScore score = retention.acknowledge(subscriberId, principal.getName());
        audit.record(principal, "retention.acknowledge",
                "Marked customer " + subscriberId + " as followed up");
        return Map.of("acknowledgedAt", score.getAcknowledgedAt(), "by", score.getAcknowledgedBy());
    }

    /** Recomputes now rather than waiting for tonight. */
    @PostMapping("/rescore")
    public Map<String, Object> rescore(Principal principal) {
        int count = retention.scoreAll();
        audit.record(principal, "retention.rescore", "Re-scored " + count + " customers");
        return Map.of("scored", count);
    }

    /**
     * Customers persistently below the speed they pay for.
     *
     * <p>Only meaningful where RADIUS accounting is switched on, since that is
     * where the measurement comes from — so an empty list means "nothing
     * measured" as often as it means "nothing wrong", and the screen says so
     * rather than showing a reassuring zero.
     */
    @GetMapping("/speed")
    public Map<String, Object> speed(@RequestParam(defaultValue = "14") int days) {
        List<Map<String, Object>> rows = speedAudit.shortfalls(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("customers", rows);
        out.put("measured", !rows.isEmpty() || !speedAudit.history(0L, days).isEmpty());
        return out;
    }

    @GetMapping("/speed/{subscriberId}")
    public Map<String, Object> speedHistory(@PathVariable Long subscriberId,
                                            @RequestParam(defaultValue = "30") int days) {
        return Map.of("days", days, "history", speedAudit.history(subscriberId, days).stream()
                .map(d -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", d.getObservedOn());
                    row.put("peakDownBps", d.getPeakDownBps());
                    row.put("peakUpBps", d.getPeakUpBps());
                    row.put("planDownBps", d.getPlanDownBps());
                    row.put("deliveredPercent", d.getDeliveredPercent());
                    row.put("samples", d.getSamples());
                    row.put("shortfall", d.isShortfall());
                    return row;
                }).toList());
    }
}
