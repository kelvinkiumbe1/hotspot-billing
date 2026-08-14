package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.BackupReport;
import com.spalimited.hotspotbilling.domain.OpsSettings;
import com.spalimited.hotspotbilling.repository.BackupReportRepository;
import com.spalimited.hotspotbilling.repository.OpsSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the backups rather than taking them.
 *
 * <p>Taking backups is {@code deploy/backup.sh}'s job and has been for a while.
 * The failure that actually loses a business is not the missing script — it is
 * the script that quietly stopped running eight weeks ago, or the one still
 * running nightly and writing a dump that would not restore. Neither shows up
 * anywhere until the day somebody needs the data back.
 *
 * <p>So the script now reports each run here, and this notices silence. The
 * same rule the revenue audit runs on: a job that says nothing must never be
 * read as a job that succeeded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackupWatchService {

    private final BackupReportRepository reports;
    private final OpsSettingsRepository settingsRepo;
    private final MessagingSettingsService messagingSettings;
    private final SmsService smsService;
    private final AuditService audit;

    /** Whether an alert has already gone out, so a missed night nags once a day. */
    private volatile Instant lastAlertAt;

    // --- Settings ---

    @Transactional
    public OpsSettings settings() {
        return settingsRepo.findById(OpsSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(OpsSettings.builder()
                        .id(OpsSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public OpsSettings saveSettings(OpsSettings in) {
        OpsSettings s = settings();
        s.setBackupWatchEnabled(in.isBackupWatchEnabled());
        s.setBackupExpectedHours(clamp(in.getBackupExpectedHours(), 1, 720));
        s.setBackupMinBytes(Math.max(0, in.getBackupMinBytes()));
        s.setHealthWatchEnabled(in.isHealthWatchEnabled());
        s.setCallbackSilenceHours(clamp(in.getCallbackSilenceHours(), 1, 72));
        s.setQuietFromHour(clamp(in.getQuietFromHour(), 0, 23));
        s.setQuietToHour(clamp(in.getQuietToHour(), 0, 23));
        s.setHeartbeatUrl(blankToNull(in.getHeartbeatUrl()));
        return settingsRepo.save(s);
    }

    // --- What the script reports ---

    /**
     * Records one run. A dump under the minimum size is filed as a failure
     * whatever the script thought: a zero-byte file that exits cleanly is the
     * most dangerous kind of backup there is.
     */
    @Transactional
    public BackupReport record(String tenant, boolean ok, long bytes, boolean verified,
                               boolean offsite, long durationMs, String error) {
        OpsSettings s = settings();
        boolean tooSmall = ok && bytes < s.getBackupMinBytes();
        String note = tooSmall
                ? "Dump was only " + bytes + " bytes, under the " + s.getBackupMinBytes() + "-byte minimum"
                : error;

        BackupReport report = reports.save(BackupReport.builder()
                .tenant(tenant == null || tenant.isBlank() ? "default" : tenant.trim())
                .ok(ok && !tooSmall)
                .bytes(Math.max(0, bytes))
                .verified(verified)
                .offsite(offsite)
                .durationMs(Math.max(0, durationMs))
                .error(trim(note))
                .reportedAt(Instant.now())
                .build());

        if (!report.isOk()) {
            audit.system("backup.failed", "Backup of " + report.getTenant() + " failed: " + report.getError());
            alert("ALERT: last night's backup of " + report.getTenant() + " failed — "
                    + (report.getError() == null ? "no reason given" : report.getError()));
        } else {
            audit.system("backup.ok", "Backup of " + report.getTenant() + " succeeded ("
                    + report.getBytes() + " bytes"
                    + (report.isVerified() ? ", restore-verified" : "")
                    + (report.isOffsite() ? ", copied off-site" : "") + ")");
            lastAlertAt = null; // a good run clears the nagging
        }
        return report;
    }

    // --- Noticing silence ---

    /**
     * Checks once an hour that a good backup has been reported recently. Hourly
     * rather than daily so a missed night is noticed the same morning, with the
     * alert itself limited to one a day.
     */
    @Scheduled(cron = "${ops.backup-check-cron:0 20 * * * *}")
    @Transactional
    public void checkForSilence() {
        OpsSettings s = settings();
        if (!s.isBackupWatchEnabled()) {
            return;
        }
        BackupReport last = reports.findFirstByOkTrueOrderByReportedAtDesc().orElse(null);
        Instant deadline = Instant.now().minus(Duration.ofHours(s.getBackupExpectedHours()));
        if (last != null && last.getReportedAt().isAfter(deadline)) {
            return;
        }
        if (lastAlertAt != null && lastAlertAt.isAfter(Instant.now().minus(Duration.ofHours(24)))) {
            return; // already said so today
        }
        String detail = last == null
                ? "No backup has ever been reported."
                : "The last good backup was " + Duration.between(last.getReportedAt(), Instant.now()).toHours()
                        + " hours ago.";
        audit.system("backup.missing", detail);
        alert("ALERT: no recent database backup. " + detail + " Check the backup job on the server.");
        lastAlertAt = Instant.now();
    }

    // --- Reading it back ---

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        OpsSettings s = settings();
        BackupReport lastGood = reports.findFirstByOkTrueOrderByReportedAtDesc().orElse(null);
        BackupReport last = reports.findFirstByOrderByReportedAtDesc().orElse(null);
        List<BackupReport> recent = reports.findTop50ByOrderByReportedAtDesc();

        boolean healthy = lastGood != null && lastGood.getReportedAt()
                .isAfter(Instant.now().minus(Duration.ofHours(s.getBackupExpectedHours())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", s);
        out.put("healthy", healthy);
        out.put("lastGoodAt", lastGood == null ? null : lastGood.getReportedAt());
        out.put("lastAt", last == null ? null : last.getReportedAt());
        out.put("lastVerified", lastGood != null && lastGood.isVerified());
        out.put("lastOffsite", lastGood != null && lastGood.isOffsite());
        out.put("recent", recent);
        return out;
    }

    // --- helpers ---

    private void alert(String message) {
        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, message);
        }
        log.warn(message);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
