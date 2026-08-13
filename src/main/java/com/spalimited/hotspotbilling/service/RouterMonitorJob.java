package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.TrafficUsage;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.TrafficUsageRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final TrafficUsageRepository trafficUsage;
    private final MikrotikService mikrotikService;
    private final SmsService smsService;
    private final AuditService audit;

    private final MessagingSettingsService messagingSettings;
    private final OperatorAlertSettingsService alertSettings;
    private final SubscriptionService subscriptionService;
    private final FupService fupService;

    /** Alert the operator when one voucher is used on several devices at once. */
    @org.springframework.beans.factory.annotation.Value("${hotspot.sharing.alert-enabled:true}")
    private boolean sharingAlertEnabled;

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
                captureTraffic(router);
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
                    // A reboot can also clear PPPoE 'disabled' flags — re-assert
                    // that suspended (lapsed) subscribers stay off, so none slip
                    // back online after the router recovers.
                    try {
                        int reasserted = 0;
                        for (Subscriber sub : subscribers.findByStatus(Subscriber.Status.SUSPENDED)) {
                            try {
                                mikrotikService.setPppoeEnabled(sub, false);
                                reasserted++;
                            } catch (Exception ignore) {
                                // one bad user shouldn't stop the rest
                            }
                        }
                        if (reasserted > 0) {
                            audit.system("router.reassert", "Re-disabled " + reasserted
                                    + " suspended subscriber(s) after " + router.getName() + " recovered");
                        }
                    } catch (Exception e) {
                        log.warn("PPPoE re-assert failed for {}: {}", router.getName(), e.getMessage());
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
     * Records hotspot data usage from the router's live per-session byte
     * counters. Those counters are cumulative and reset when a session ends or
     * the router reboots, so — exactly like {@link #snapshotHotspotUsage} does
     * for time — we keep the last counters per voucher and fold only the delta
     * into an hourly (router, user) row. Every traffic report is then an
     * aggregation of the traffic_usage table.
     */
    private void captureTraffic(Router router) {
        List<Map<String, String>> sessions;
        try {
            sessions = mikrotikService.activeSessions(router);
        } catch (Exception e) {
            log.debug("Traffic capture skipped for {}: {}", router.getName(), e.getMessage());
            return;
        }
        detectSharing(router, sessions);
        Instant bucket = Instant.now().truncatedTo(ChronoUnit.HOURS);
        for (Map<String, String> session : sessions) {
            if (!"hotspot".equals(session.get("kind"))) {
                continue; // PPPoE byte counters aren't read yet (reported as 0)
            }
            String code = session.get("user");
            if (code == null || code.isBlank()) {
                continue;
            }
            // bytes-in = received from the client (upload); bytes-out = sent to
            // the client (download), in RouterOS hotspot terms.
            long observedUp = MikrotikService.parseBytes(session.get("bytesIn")).longValue();
            long observedDown = MikrotikService.parseBytes(session.get("bytesOut")).longValue();

            Voucher voucher = vouchers.findByCode(code).orElse(null);
            if (voucher == null) {
                continue; // unknown user — no cursor to diff against safely
            }
            long deltaUp = observedUp >= voucher.getLastBytesIn() ? observedUp - voucher.getLastBytesIn() : observedUp;
            long deltaDown = observedDown >= voucher.getLastBytesOut() ? observedDown - voucher.getLastBytesOut() : observedDown;
            voucher.setLastBytesIn(observedUp);
            voucher.setLastBytesOut(observedDown);
            voucher.setUsedBytes(voucher.getUsedBytes() + Math.max(0, deltaUp) + Math.max(0, deltaDown));
            if (voucher.getRouterId() == null) {
                voucher.setRouterId(router.getId());
            }
            vouchers.save(voucher);

            // Fair-use: throttle/block/notify once the pass crosses its cap.
            try {
                fupService.enforce(router, voucher);
            } catch (Exception e) {
                log.warn("FUP enforcement failed for voucher {}: {}", voucher.getId(), e.getMessage());
            }

            if (deltaUp <= 0 && deltaDown <= 0) {
                continue;
            }
            Long planId = voucher.getPlan() != null ? voucher.getPlan().getId() : null;
            TrafficUsage row = trafficUsage
                    .findByBucketHourAndRouterIdAndUserKey(bucket, router.getId(), code)
                    .orElseGet(() -> TrafficUsage.builder()
                            .bucketHour(bucket)
                            .routerId(router.getId())
                            .userKey(code)
                            .planId(planId)
                            .bytesUp(0)
                            .bytesDown(0)
                            .build());
            row.setBytesUp(row.getBytesUp() + Math.max(0, deltaUp));
            row.setBytesDown(row.getBytesDown() + Math.max(0, deltaDown));
            if (row.getPlanId() == null) {
                row.setPlanId(planId);
            }
            trafficUsage.save(row);
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

    /**
     * Flags voucher sharing: one code active on two or more distinct devices at
     * the same time. Alerts the operator once per pass (guarded by
     * {@code sharingFlaggedAt}); it stays an alert rather than an auto-kick so a
     * customer's own phone-plus-laptop isn't punished.
     */
    private void detectSharing(Router router, List<Map<String, String>> sessions) {
        if (!sharingAlertEnabled) {
            return;
        }
        Map<String, java.util.Set<String>> macsByCode = new java.util.HashMap<>();
        for (Map<String, String> session : sessions) {
            if (!"hotspot".equals(session.get("kind"))) {
                continue;
            }
            String code = session.get("user");
            String mac = session.get("macAddress");
            if (code == null || code.isBlank() || mac == null || mac.isBlank()) {
                continue;
            }
            macsByCode.computeIfAbsent(code, k -> new java.util.HashSet<>()).add(mac);
        }
        for (Map.Entry<String, java.util.Set<String>> entry : macsByCode.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue; // a single device — normal
            }
            Voucher v = vouchers.findByCode(entry.getKey()).orElse(null);
            if (v == null || v.getSharingFlaggedAt() != null) {
                continue;
            }
            audit.system("voucher.sharing", "Voucher " + entry.getKey() + " active on "
                    + entry.getValue().size() + " devices at once on " + router.getName());
            String alertPhone = messagingSettings.alertPhone();
            if (alertPhone != null && !alertPhone.isBlank()) {
                smsService.trySend(alertPhone, "ALERT: access code " + entry.getKey() + " is being used on "
                        + entry.getValue().size() + " devices at once (possible sharing).");
            }
            v.setSharingFlaggedAt(Instant.now());
            vouchers.save(v);
            log.info("Flagged voucher {} as shared across {} devices", entry.getKey(), entry.getValue().size());
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
