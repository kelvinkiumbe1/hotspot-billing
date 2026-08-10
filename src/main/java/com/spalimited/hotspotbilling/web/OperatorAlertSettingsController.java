package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.OperatorAlertSettingsService;
import com.spalimited.hotspotbilling.service.SalesDigestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/** Operator alerts, outage compensation and the sales digest. */
@RestController
@RequestMapping("/api/admin/settings/alerts")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class OperatorAlertSettingsController {

    private final OperatorAlertSettingsService service;
    private final SalesDigestService salesDigest;
    private final AuditService audit;

    @GetMapping
    public OperatorAlertSettings get() {
        return service.get();
    }

    @PutMapping
    public OperatorAlertSettings update(@RequestBody OperatorAlertSettings body, Principal principal) {
        OperatorAlertSettings saved = service.update(body);
        audit.record(principal, "settings.alerts", "Updated operator alerts and digest");
        return saved;
    }

    /** Sends the digest right now, so the operator can see what it looks like. */
    @PostMapping("/digest/test")
    public Map<String, Object> testDigest(Principal principal) {
        String preview = salesDigest.buildAndSend();
        audit.record(principal, "settings.alerts.digest.test", "Sent a test sales digest");
        return Map.of("message", "Sent to your alert phone/email.", "preview", preview);
    }
}
