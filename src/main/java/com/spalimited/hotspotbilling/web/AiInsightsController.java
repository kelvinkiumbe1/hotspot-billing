package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.AiInsightsService;
import com.spalimited.hotspotbilling.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * The dashboard "ops copilot": grounded, actionable insights any signed-in
 * office user can see, plus the one-tap actions (gated to the right role).
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AiInsightsController {

    private final AiInsightsService insights;
    private final AuditService audit;

    @GetMapping("/insights")
    public Map<String, Object> insights() {
        return insights.insights();
    }

    /** Send an expiry reminder to every customer lapsing in the next few days. */
    @PostMapping("/act/remind-lapsing")
    @PreAuthorize("hasAuthority('OUTREACH')")
    public Map<String, Object> remindLapsing(Principal principal) {
        int sent = insights.remindLapsing();
        audit.record(principal, "ai.remind-lapsing", "Reminded " + sent + " lapsing customer(s)");
        return Map.of("sent", sent);
    }
}
