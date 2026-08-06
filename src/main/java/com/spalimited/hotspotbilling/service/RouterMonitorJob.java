package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final MikrotikService mikrotikService;
    private final SmsService smsService;
    private final AuditService audit;

    @org.springframework.beans.factory.annotation.Value("${app.alert-phone:}")
    private String alertPhone;

    @Scheduled(fixedDelay = 120_000, initialDelay = 20_000)
    @Transactional
    public void run() {
        if (!mikrotikService.settings().isEnabled()) {
            return;
        }
        for (Router router : routers.findByEnabledTrue()) {
            boolean wasOnline = router.isOnline();
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

            if (wasOnline && !online) {
                audit.system("router.offline", "Router " + router.getName() + " went offline: " + router.getLastError());
                if (alertPhone != null && !alertPhone.isBlank()) {
                    smsService.trySend(alertPhone, "ALERT: SPA WiFi router '" + router.getName() + "' is offline.");
                }
                log.warn("Router {} went offline", router.getName());
            } else if (!wasOnline && online) {
                audit.system("router.online", "Router " + router.getName() + " is back online");
                if (alertPhone != null && !alertPhone.isBlank()) {
                    smsService.trySend(alertPhone, "Recovered: SPA WiFi router '" + router.getName() + "' is back online.");
                }
            }
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
