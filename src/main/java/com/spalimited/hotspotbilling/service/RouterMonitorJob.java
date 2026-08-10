package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Polls every enabled router: records uptime/version/session counts,
 * flags routers that go offline (with an SMS alert to the admin), and
 * refreshes per-subscriber usage and last-seen times.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RouterMonitorJob {

    private final RouterRepository routers;
    private final SubscriberRepository subscribers;
    private final VoucherRepository vouchers;
    private final MikrotikService mikrotikService;
    private final SmsService smsService;
    private final AuditService audit;

    private final MessagingSettingsService messagingSettings;
    private final OperatorAlertSettingsService alertSettings;
    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelay = 120_000, initialDelay = 20_000)
    @Transactional
    public void run() {
        if (!mikrotikService.settings().isEnabled()) {
            return;
        }
        for (Router router : routers.findByEnabledTrue()) {
            boolean wasOnline = router.isOnline();
            Instant lastSeenBefore = router.getLastSeenAt();
            Map<String, Object> probe = mikrotikService.probe(router);
            boolean online = Boolean.TRUE.equals(probe.get("online"));

            router.setOnline(online);
            router.setLastCheckedAt(Instant.now());
            if (online) {
                router.setLastSeenAt(Instant.now());
                router.setLastError(null);
                router.setUptime((String) probe.get("uptime"));
                router.setRouterOsVersion((String) probe.get("version"));
                router.setBoardName((String) probe.get("boardName"));
                router.setActiveHotspotUsers((Integer) probe.get("hotspotUsers"));
                router.setActivePppoeUsers((Integer) probe.get("pppoeUsers"));
                refreshUsage(router);
            } else {
                router.setLastError((String) probe.get("error"));
            }
            routers.save(router);

            boolean alertsOn = alertSettings.get().isRouterOfflineAlert();
            if (wasOnline && !online) {
                audit.system("router.offline", "Router " + router.getName() + " went offline: " + router.getLastError());
                String alertPhone = messagingSettings.alertPhone();
                if (alertsOn && alertPhone != null && !alertPhone.isBlank()) {
                    smsService.trySend(alertPhone, "ALERT: SPA WiFi router '" + router.getName() + "' is offline.");
                }
                log.warn("Router {} went offline", router.getName());
            } else if (!wasOnline && online) {
                audit.system("router.online", "Router " + router.getName() + " is back online");
                String alertPhone = messagingSettings.alertPhone();
                if (alertsOn && alertPhone != null && !alertPhone.isBlank()) {
                    smsService.trySend(alertPhone, "Recovered: SPA WiFi router '" + router.getName() + "' is back online.");
                }
                compensateForOutage(router, lastSeenBefore);
                // A reboot can wipe the hotspot users. Re-create any that are
                // missing and pin everyone to their remaining time, so paid
                // customers reconnect and continue from where they left off.
                if (router.isDefaultRouter()) {
                    try {
                        List<Voucher> active = vouchers.findByStatusIn(
                                List.of(Voucher.Status.UNUSED, Voucher.Status.ACTIVE));
                        int restored = mikrotikService.reconcileHotspotUsers(router, active);
                        if (restored > 0) {
                            audit.system("router.reconcile", "Re-created " + restored
                                    + " hotspot user(s) on " + router.getName() + " after it recovered");
                        }
                    } catch (Exception e) {
                        log.warn("Hotspot reconcile failed for {}: {}", router.getName(), e.getMessage());
                    }
                }
            }

            // Keep the app's used-time record current so, if the router later
            // loses its counters, reconcile still knows how much is left.
            if (online && router.isDefaultRouter()) {
                snapshotHotspotUsage(router);
            }
        }
    }

    /**
     * Reads each hotspot user's uptime from the router and folds it into the
     * voucher's authoritative used-time. Adds only the delta since the last
     * poll, and treats a counter that went backwards (a reboot, or our own
     * reconcile resetting it) as counting up from zero.
     */
    private void snapshotHotspotUsage(Router router) {
        Map<String, Long> uptimes;
        try {
            uptimes = mikrotikService.hotspotUserUptimes(router);
        } catch (Exception e) {
            log.debug("Hotspot usage snapshot skipped for {}: {}", router.getName(), e.getMessage());
            return;
        }
        for (Map.Entry<String, Long> entry : uptimes.entrySet()) {
            vouchers.findByCode(entry.getKey()).ifPresent(v -> {
                long observed = entry.getValue();
                long prev = v.getRouterUptimeSeconds();
                long delta = observed >= prev ? observed - prev : observed;
                if (delta > 0) {
                    v.setUsedSeconds(v.getUsedSeconds() + delta);
                }
                v.setRouterUptimeSeconds(observed);
                // A voucher used at the hotspot login (not via the redeem page)
                // is still UNUSED in the app until we see it here.
                if (v.getStatus() == Voucher.Status.UNUSED && v.getUsedSeconds() > 0) {
                    v.setStatus(Voucher.Status.ACTIVE);
                    if (v.getActivatedAt() == null) {
                        v.setActivatedAt(Instant.now());
                    }
                }
                if (v.getStatus() == Voucher.Status.ACTIVE && v.isExhausted()) {
                    v.setStatus(Voucher.Status.EXPIRED);
                }
                vouchers.save(v);
            });
        }
    }

    /**
     * When the operator has opted in, credits active subscribers for a
     * network outage by pushing their expiry back by the downtime. Only the
     * default router (the one carrying paid customers) triggers this, and
     * only outages past the configured minimum are counted.
     */
    private void compensateForOutage(Router router, Instant lastSeenBefore) {
        if (!router.isDefaultRouter() || lastSeenBefore == null) {
            return;
        }
        var settings = alertSettings.get();
        if (!settings.isOutageCompensationEnabled()) {
            return;
        }
        java.time.Duration downtime = java.time.Duration.between(lastSeenBefore, Instant.now());
        if (downtime.toMinutes() < settings.getMinOutageMinutes()) {
            return;
        }
        try {
            int credited = subscriptionService.compensateForOutage(downtime);
            if (credited > 0) {
                audit.system("outage.compensate", "Extended " + credited + " subscriber(s) by "
                        + downtime.toMinutes() + " min after a " + downtime.toMinutes() + "-minute outage on "
                        + router.getName());
            }
        } catch (Exception e) {
            log.warn("Outage compensation failed for {}: {}", router.getName(), e.getMessage());
        }
    }

    /** Maps live PPPoE sessions back to subscribers for last-seen tracking. */
    private void refreshUsage(Router router) {
        try {
            for (Map<String, String> session : mikrotikService.activeSessions(router)) {
                if (!"pppoe".equals(session.get("kind"))) {
                    continue;
                }
                String user = session.get("user");
                if (user == null || user.isBlank()) {
                    continue;
                }
                subscribers.findByPppoeUsername(user).ifPresent(sub -> {
                    sub.setLastSeenOnlineAt(Instant.now());
                    subscribers.save(sub);
                });
            }
        } catch (Exception e) {
            log.debug("Usage refresh skipped for {}: {}", router.getName(), e.getMessage());
        }
    }

    /** Monthly usage reset so counters track the billing cycle. */
    @Scheduled(cron = "0 5 0 1 * *")
    @Transactional
    public void resetMonthlyUsage() {
        for (Subscriber sub : subscribers.findAll()) {
            sub.setDataUsedMb(0L);
            sub.setUsageResetAt(Instant.now());
            subscribers.save(sub);
        }
        audit.system("usage.reset", "Monthly data-usage counters reset");
    }
}
