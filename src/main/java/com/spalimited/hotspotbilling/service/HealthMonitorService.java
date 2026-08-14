package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Watches the system rather than the network.
 *
 * <p>Everything else here monitors routers. Nothing has ever monitored the
 * thing doing the monitoring, and its failures are the quiet kind: the M-Pesa
 * callback URL gets de-registered and payments simply stop arriving; the
 * scheduler dies and nobody is suspended, no renewal is retried, no lost
 * callback is reconciled; the SMS credit runs out and every code sent since
 * lunchtime went nowhere. None of these throw anything. They all look like a
 * quiet day.
 *
 * <p>Each check either raises an alert or clears one, keyed so a fault that
 * lasts a week is one ageing row and one text rather than seven. The operator
 * is told once when it starts and once when it clears.
 *
 * <p>The one failure this cannot report on is itself being dead, so if an
 * external watchdog URL is configured it is pinged every cycle: when the pings
 * stop, that service raises the alarm this one no longer can.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HealthMonitorService {

    /** Jobs worth watching, and how long silence from each may reasonably last. */
    private static final Map<String, Duration> WATCHED_JOBS = Map.of(
            "router-monitor", Duration.ofMinutes(20),
            "payment-reconcile", Duration.ofMinutes(20),
            "subscriptions", Duration.ofHours(4),
            "dunning", Duration.ofHours(2));

    /** Friendlier names for the alert text than the keys above. */
    private static final Map<String, String> JOB_LABELS = Map.of(
            "router-monitor", "router monitoring",
            "payment-reconcile", "payment reconciliation",
            "subscriptions", "subscription expiry and suspension",
            "dunning", "failed-payment recovery");

    private final HealthAlertRepository alerts;
    private final JobHeartbeatRepository heartbeats;
    private final PaymentRepository payments;
    private final C2bPaymentRepository c2bPayments;
    private final OutboundMessageRepository outbound;
    private final RouterRepository routers;
    private final BackupWatchService backupWatch;
    private final PaymentGatewayService gateways;
    private final MessagingSettingsService messagingSettings;
    private final SmsService smsService;
    private final AuditService audit;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** One finding from a check, before it is merged with what is stored. */
    private record Finding(String key, HealthAlert.Severity severity, String title, String detail) {
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    @Transactional
    public void run() {
        OpsSettings s = backupWatch.settings();
        pingWatchdog(s);
        if (!s.isHealthWatchEnabled()) {
            return;
        }

        List<Finding> found = new ArrayList<>();
        found.addAll(checkPaymentSilence(s));
        found.addAll(checkJobs());
        found.addAll(checkMessaging());
        found.addAll(checkRouters());

        Instant now = Instant.now();
        Set<String> seen = new HashSet<>();
        for (Finding f : found) {
            seen.add(f.key());
            HealthAlert alert = alerts.findByCheckKey(f.key()).orElse(null);
            boolean isNew = alert == null || alert.getStatus() == HealthAlert.Status.RESOLVED;
            if (alert == null) {
                alert = HealthAlert.builder().checkKey(f.key()).firstSeenAt(now).build();
            } else if (isNew) {
                alert.setFirstSeenAt(now);
                alert.setResolvedAt(null);
                alert.setNotifiedAt(null);
            }
            alert.setSeverity(f.severity());
            alert.setTitle(f.title());
            alert.setDetail(f.detail());
            alert.setStatus(HealthAlert.Status.OPEN);
            alert.setLastSeenAt(now);
            if (isNew) {
                alert.setNotifiedAt(now);
                audit.system("health.alert", f.title() + " — " + f.detail());
                notifyOperator((f.severity() == HealthAlert.Severity.CRITICAL ? "CRITICAL: " : "WARNING: ")
                        + f.title() + ". " + f.detail());
            }
            alerts.save(alert);
        }

        for (HealthAlert open : alerts.findByStatus(HealthAlert.Status.OPEN)) {
            if (seen.contains(open.getCheckKey())) {
                continue;
            }
            open.setStatus(HealthAlert.Status.RESOLVED);
            open.setResolvedAt(now);
            alerts.save(open);
            audit.system("health.clear", open.getTitle() + " has cleared");
            notifyOperator("Cleared: " + open.getTitle() + ".");
        }
    }

    // --- The checks ---

    /**
     * Money arriving is the one signal that proves the whole payment path works
     * end to end — Daraja reachable, the callback URL registered, the guard
     * letting it through, the handler not throwing. Silence during trading
     * hours is the earliest warning that something in that chain has broken.
     *
     * <p>Only meaningful once the system has actually taken money before, so a
     * fresh install is not permanently alarmed about the sales it never made.
     */
    private List<Finding> checkPaymentSilence(OpsSettings s) {
        if (!gateways.stkAvailable() || isQuietHours(s)) {
            return List.of();
        }
        Payment lastPayment = payments.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCESS && p.getCompletedAt() != null)
                .findFirst().orElse(null);
        C2bPayment lastC2b = c2bPayments.findTop200ByOrderByCreatedAtDesc().stream()
                .findFirst().orElse(null);
        if (lastPayment == null && lastC2b == null) {
            return List.of(); // never taken a payment; nothing to compare against
        }
        Instant latest = max(
                lastPayment == null ? null : lastPayment.getCompletedAt(),
                lastC2b == null ? null : lastC2b.getCreatedAt());
        Duration silence = Duration.between(latest, Instant.now());
        if (silence.toHours() < s.getCallbackSilenceHours()) {
            return List.of();
        }
        return List.of(new Finding("mpesa.silence", HealthAlert.Severity.CRITICAL,
                "No M-Pesa payment has arrived in " + silence.toHours() + " hours",
                "Either nobody is buying, or the callback URL is no longer reachable. "
                        + "Check Settings → Payment gateways and that Safaricom can still reach this server."));
    }

    /** A scheduler that has quietly stopped looks exactly like a quiet day. */
    private List<Finding> checkJobs() {
        List<Finding> out = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<String, Duration> entry : WATCHED_JOBS.entrySet()) {
            JobHeartbeat beat = heartbeats.findById(entry.getKey()).orElse(null);
            if (beat == null) {
                continue; // never seen it — the app may have only just started
            }
            Duration since = Duration.between(beat.getLastRunAt(), now);
            if (since.compareTo(entry.getValue()) <= 0) {
                continue;
            }
            String label = JOB_LABELS.getOrDefault(entry.getKey(), entry.getKey());
            out.add(new Finding("job." + entry.getKey(), HealthAlert.Severity.CRITICAL,
                    "Background " + label + " has stopped running",
                    "Last ran " + since.toMinutes() + " minutes ago. Restarting the application usually "
                            + "clears this; until it runs, that work is not happening at all."));
        }
        return out;
    }

    /** Codes that are never delivered are indistinguishable from codes never sent. */
    private List<Finding> checkMessaging() {
        List<OutboundMessage> recent = outbound.findByCreatedAtAfter(Instant.now().minus(Duration.ofHours(1)));
        if (recent.size() < 5) {
            return List.of(); // too few to draw any conclusion from
        }
        long failed = recent.stream().filter(m -> m.getStatus() == OutboundMessage.Status.FAILED).count();
        if (failed * 2 < recent.size()) {
            return List.of();
        }
        return List.of(new Finding("messaging.failing", HealthAlert.Severity.CRITICAL,
                failed + " of the last " + recent.size() + " messages failed to send",
                "Customers are not receiving their codes. Check the SMS/WhatsApp credentials and "
                        + "whether the account still has credit."));
    }

    /**
     * Every router down at once is a different problem from one router down —
     * it points at this server's own connectivity rather than at a site, and it
     * means nothing else this system reports can be trusted right now.
     */
    private List<Finding> checkRouters() {
        List<Router> enabled = routers.findByEnabledTrue();
        if (enabled.size() < 2) {
            return List.of(); // one router down is the existing per-router alert's job
        }
        boolean allDown = enabled.stream().noneMatch(Router::isOnline);
        if (!allDown) {
            return List.of();
        }
        return List.of(new Finding("routers.all-down", HealthAlert.Severity.CRITICAL,
                "All " + enabled.size() + " routers are unreachable at once",
                "That usually means this server has lost its connection rather than every site "
                        + "failing together. Nothing else reported here is reliable until it returns."));
    }

    // --- The watchdog ---

    /**
     * Pings an external watchdog. This is the inverse of every other check: it
     * says nothing when things are wrong, and its silence is what raises the
     * alarm — which is the only way a dead application can report itself.
     */
    private void pingWatchdog(OpsSettings s) {
        String url = s.getHeartbeatUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            http.send(HttpRequest.newBuilder(URI.create(url.trim()))
                            .timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Watchdog ping failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    // --- Reading it back ---

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        List<HealthAlert> open = alerts.findByStatus(HealthAlert.Status.OPEN).stream()
                .sorted(Comparator.comparing(HealthAlert::getSeverity)
                        .thenComparing(HealthAlert::getFirstSeenAt))
                .toList();
        List<Map<String, Object>> jobs = new ArrayList<>();
        WATCHED_JOBS.forEach((name, maxAge) -> {
            JobHeartbeat beat = heartbeats.findById(name).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("label", JOB_LABELS.getOrDefault(name, name));
            row.put("lastRunAt", beat == null ? null : beat.getLastRunAt());
            // Three states, not two: a job the app has never seen run has an
            // unknown state, and showing that as a fault would mean every
            // restart looks like a broken system for its first two minutes.
            row.put("status", beat == null ? "unknown"
                    : Duration.between(beat.getLastRunAt(), Instant.now()).compareTo(maxAge) <= 0
                            ? "ok" : "stale");
            jobs.add(row);
        });
        jobs.sort(Comparator.comparing(r -> String.valueOf(r.get("label"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", backupWatch.settings());
        out.put("open", open);
        out.put("recent", alerts.findTop50ByOrderByLastSeenAtDesc().stream()
                .filter(a -> a.getStatus() == HealthAlert.Status.RESOLVED)
                .limit(20)
                .toList());
        out.put("jobs", jobs);
        out.put("backups", backupWatch.overview());
        return out;
    }

    // --- helpers ---

    private void notifyOperator(String message) {
        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, message);
        }
        log.warn(message);
    }

    /** Overnight, an absence of sales is just the middle of the night. */
    private boolean isQuietHours(OpsSettings s) {
        int hour = LocalTime.now(ZoneId.systemDefault()).getHour();
        int from = s.getQuietFromHour();
        int to = s.getQuietToHour();
        if (from == to) {
            return false;
        }
        return from < to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    private static Instant max(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
