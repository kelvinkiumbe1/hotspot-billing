package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.RevenueAuditSettings;
import com.spalimited.hotspotbilling.domain.RevenueFinding;
import com.spalimited.hotspotbilling.service.RevenueAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * Revenue assurance: what the nightly audit found, and the two things an
 * operator can do about each finding — deal with it, or say it's expected.
 */
@RestController
@RequestMapping("/api/admin/revenue-audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FINANCE')")
public class RevenueAuditController {

    private final RevenueAuditService revenueAudit;

    @GetMapping
    public Map<String, Object> overview() {
        return revenueAudit.overview();
    }

    /** Runs the sweep on demand rather than waiting for tonight. */
    @PostMapping("/run")
    public Map<String, Object> run(Principal principal) {
        return revenueAudit.sweep(principal != null ? principal.getName() : "system");
    }

    public record CloseRequest(String note) {
    }

    @PostMapping("/{id}/resolve")
    public RevenueFinding resolve(@PathVariable Long id, @RequestBody(required = false) CloseRequest body,
                                  Principal principal) {
        return revenueAudit.resolve(id, principal != null ? principal.getName() : null,
                body == null ? null : body.note());
    }

    @PostMapping("/{id}/ignore")
    public RevenueFinding ignore(@PathVariable Long id, @RequestBody(required = false) CloseRequest body,
                                 Principal principal) {
        return revenueAudit.ignore(id, principal != null ? principal.getName() : null,
                body == null ? null : body.note());
    }

    @PutMapping("/settings")
    public RevenueAuditSettings saveSettings(@RequestBody RevenueAuditSettings in) {
        return revenueAudit.saveSettings(in);
    }
}
