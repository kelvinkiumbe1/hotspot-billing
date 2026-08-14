package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.BackupReport;
import com.spalimited.hotspotbilling.domain.OpsSettings;
import com.spalimited.hotspotbilling.service.BackupWatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Operational assurance: what the backup script reports, and how closely the
 * system watches itself.
 *
 * <p>The report endpoint is meant to be called by {@code deploy/backup.sh} on
 * the server, authenticating with a long-lived API token (Settings → API
 * tokens) rather than a staff password.
 */
@RestController
@RequestMapping("/api/admin/ops")
@RequiredArgsConstructor
public class OpsController {

    private final BackupWatchService backups;
    private final com.spalimited.hotspotbilling.service.HealthMonitorService health;

    /** Everything the system knows about its own state. */
    @GetMapping("/health")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> health() {
        return health.overview();
    }

    /** Runs every check now rather than waiting for the next sweep. */
    @PostMapping("/health/check")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> checkNow() {
        health.run();
        return health.overview();
    }

    public record BackupReportRequest(String tenant, Boolean ok, Long bytes, Boolean verified,
                                      Boolean offsite, Long durationMs, String error) {
    }

    @PostMapping("/backup-report")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public BackupReport report(@RequestBody BackupReportRequest body) {
        return backups.record(
                body.tenant(),
                !Boolean.FALSE.equals(body.ok()),
                body.bytes() == null ? 0 : body.bytes(),
                Boolean.TRUE.equals(body.verified()),
                Boolean.TRUE.equals(body.offsite()),
                body.durationMs() == null ? 0 : body.durationMs(),
                body.error());
    }

    @GetMapping("/backups")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> backups() {
        return backups.overview();
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public OpsSettings getSettings() {
        return backups.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public OpsSettings saveSettings(@RequestBody OpsSettings in) {
        return backups.saveSettings(in);
    }
}
