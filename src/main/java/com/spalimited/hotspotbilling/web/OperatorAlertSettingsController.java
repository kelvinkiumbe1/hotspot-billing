package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.OperatorAlertSettingsService;
import com.spalimited.hotspotbilling.service.DailyBriefService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/** Operator alerts, outage compensation and the daily briefing. */
@RestController
@RequestMapping("/api/admin/settings/alerts")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class OperatorAlertSettingsController {

    private final OperatorAlertSettingsService service;
    private final DailyBriefService dailyBrief;
    private final AuditService audit;

    @GetMapping
    public OperatorAlertSettings get() {
        return service.get();
    }

    @PutMapping
    public OperatorAlertSettings update(@RequestBody OperatorAlertSettings body, Principal principal) {
        OperatorAlertSettings saved = service.update(body);
        audit.record(principal, "settings.alerts", "Updated operator alerts and the daily briefing");
        return saved;
    }

    /** Sends the briefing right now, so the operator can see what it looks like. */
    @PostMapping("/digest/test")
    public Map<String, Object> testDigest(Principal principal) {
        String preview = dailyBrief.buildAndSend();
        audit.record(principal, "settings.alerts.digest.test", "Sent a test daily briefing");
        return Map.of("message", "Sent to your alert phone/email.", "preview", preview);
    }

    /** The briefing as it stands, without sending anything. */
    @GetMapping("/digest/preview")
    public Map<String, Object> previewDigest() {
        DailyBriefService.Brief brief = dailyBrief.build();
        return Map.of("preview", brief.shortForm(), "full", brief.longForm());
    }
}
