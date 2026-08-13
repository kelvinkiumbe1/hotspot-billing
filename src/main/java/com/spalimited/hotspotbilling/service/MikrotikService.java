package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.MikrotikProperties;
import com.spalimited.hotspotbilling.domain.MikrotikSettings;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.MikrotikSettingsRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.legrange.mikrotik.ApiConnection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to MikroTik RouterOS: hotspot users for vouchers, PPP secrets for
 * monthly subscribers, MAC binding, and live status for monitoring.
 *
 * Connection details live per {@link Router} so several sites can be
 * managed from one dashboard; global behaviour flags (MAC binding, hotspot
 * server names) stay in the singleton {@link MikrotikSettings}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MikrotikService {

    private final MikrotikProperties props;
    private final MikrotikSettingsRepository settingsRepository;
    private final RouterRepository routers;
    private final VoucherRepository voucherRepository;

    // --- Global settings & router resolution ---

    /** Global flags, seeded from application.properties on first use. */
    public MikrotikSettings settings() {
        return settingsRepository.findById(MikrotikSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepository.save(MikrotikSettings.builder()
                        .id(MikrotikSettings.SINGLETON_ID)
                        .enabled(props.enabled())
                        .host(props.host())
                        .port(props.port() > 0 ? props.port() : 8728)
                        .username(props.username())
                        .password(props.password())
                        .useSsl(false)
                        .build()));
    }

    public MikrotikSettings updateSettings(MikrotikSettings updated) {
        updated.setId(MikrotikSettings.SINGLETON_ID);
        return settingsRepository.save(updated);
    }

    /**
     * Returns the default router, or {@code null} if none exists. It only
     * migrates the legacy single-router settings into a first "Main Router"
     * row once MikroTik is actually enabled — a fresh account with MikroTik
     * off stays empty instead of showing a phantom router the ISP never added.
     * Every caller already null-guards via {@link #live(Router)}.
     */
    @Transactional
    public Router defaultRouter() {
        return routers.findFirstByDefaultRouterTrue()
                .or(() -> routers.findAllByOrderByNameAsc().stream().findFirst())
                .orElseGet(() -> {
                    MikrotikSettings s = settings();
                    if (!s.isEnabled()) {
                        return null; // don't fabricate a placeholder while MikroTik is off
                    }
                    return routers.save(Router.builder()
                            .name("Main Router")
                            .host(s.getHost() != null ? s.getHost() : "192.168.88.1")
                            .port(s.getPort() > 0 ? s.getPort() : 8728)
                            .username(s.getUsername())
                            .password(s.getPassword())
                            .useSsl(s.isUseSsl())
                            .enabled(s.isEnabled())
                            .defaultRouter(true)
                            .build());
                });
    }

    /** The router a subscriber lives on, falling back to the default. */
    @Transactional
    public Router routerFor(Long routerId) {
        if (routerId != null) {
            return routers.findById(routerId).orElseGet(this::defaultRouter);
        }
        return defaultRouter();
    }

    /** True when we should actually talk to hardware. */
    private boolean live(Router router) {
        return settings().isEnabled() && router != null && router.isEnabled()
                && router.getHost() != null && !router.getHost().isBlank();
    }

    // --- Connection plumbing ---

    private ApiConnection open(Router router) throws Exception {
        SocketFactory factory = router.isUseSsl() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        int port = router.getPort() > 0 ? router.getPort() : (router.isUseSsl() ? 8729 : 8728);
        return ApiConnection.connect(factory, router.getHost(), port, ApiConnection.DEFAULT_CONNECTION_TIMEOUT);
    }

    private ApiConnection login(Router router) throws Exception {
        ApiConnection connection = open(router);
        connection.login(router.getUsername(), router.getPassword());
        return connection;
    }

    /** Connects and logs in — used by the admin "Test Connection" button. */
    public void testConnection(Router router) {
        try (ApiConnection connection = login(router)) {
            connection.execute("/system/identity/print");
        } catch (Exception e) {
            throw new IllegalStateException("Connection failed: " + e.getMessage(), e);
        }
    }

    /** Legacy entry point: tests the settings-shaped payload against a transient router. */
    public void testConnection(MikrotikSettings s) {
        testConnection(Router.builder()
                .name("test")
                .host(s.getHost())
                .port(s.getPort())
                .username(s.getUsername())
                .password(s.getPassword())
                .useSsl(s.isUseSsl())
                .enabled(true)
                .build());
    }

    // --- Hotspot vouchers ---

    public void provisionVoucher(Voucher voucher) {
        Router router = defaultRouter();
        if (!live(router)) {
            log.info("MikroTik disabled — skipping provisioning of voucher {}", voucher.getCode());
            return;
        }
        String limitUptime = voucher.getEffectiveDurationMinutes() + "m";
        try (ApiConnection connection = login(router)) {
            String profile = ensureHotspotProfile(connection, voucher.getPlan());
            connection.execute(String.format(
                    "/ip/hotspot/user/add name=%s password=%s profile=%s limit-uptime=%s",
                    voucher.getCode(), voucher.getCode(), profile, limitUptime));
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Provisioned hotspot user for voucher {}", voucher.getCode());
    }

    /** Code → connect-time used, read from the router's per-user uptime counter. */
    public Map<String, Long> hotspotUserUptimes(Router router) {
        Map<String, Long> out = new HashMap<>();
        if (!live(router)) {
            return out;
        }
        try (ApiConnection connection = login(router)) {
            for (Map<String, String> u : connection.execute("/ip/hotspot/user/print")) {
                String name = u.get("name");
                if (name != null && !name.isBlank()) {
                    out.put(name, parseUptime(u.get("uptime")));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read hotspot users from {}: {}", router.getName(), e.getMessage());
        }
        return out;
    }

    /**
     * Re-establishes every still-valid voucher on a router that has just come
     * back online. A reboot can drop the hotspot users entirely (a hard power
     * cut, a config reset, a replaced board), which would lock out customers
     * who have paid and still have time. For each voucher this re-adds it if
     * missing, and in every case pins the router's remaining time to what the
     * app knows is left — so people continue from where they were, not with a
     * fresh full pass. Returns how many users had to be re-created.
     */
    @Transactional
    public int reconcileHotspotUsers(Router router, List<Voucher> vouchers) {
        if (!live(router)) {
            return 0;
        }
        int restored = 0;
        try (ApiConnection connection = login(router)) {
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (Map<String, String> u : connection.execute("/ip/hotspot/user/print")) {
                existing.add(u.get("name"));
            }
            for (Voucher v : vouchers) {
                if (v.isExhausted()) {
                    continue; // no time left — leave it to expire/be removed
                }
                long remainingMinutes = Math.max(1, (long) Math.ceil(v.getRemainingSeconds() / 60.0));
                String limit = remainingMinutes + "m";
                String profile = ensureHotspotProfile(connection, v.getPlan());
                // Keep it locked to its device across the reboot when MAC-bound.
                String mac = v.getBoundMac() != null && !v.getBoundMac().isBlank()
                        ? " mac-address=" + v.getBoundMac() : "";
                if (existing.contains(v.getCode())) {
                    // The router kept the user; its own counter may have survived,
                    // reset, or drifted — make the app's remaining time authoritative.
                    connection.execute(String.format(
                            "/ip/hotspot/user/set [find name=%s] profile=%s limit-uptime=%s%s",
                            v.getCode(), profile, limit, mac));
                    try {
                        connection.execute("/ip/hotspot/user/reset-counters [find name=" + v.getCode() + "]");
                    } catch (Exception ignore) {
                        // reset-counters is best-effort; limit-uptime still caps them.
                    }
                } else {
                    connection.execute(String.format(
                            "/ip/hotspot/user/add name=%s password=%s profile=%s limit-uptime=%s%s",
                            v.getCode(), v.getCode(), profile, limit, mac));
                    restored++;
                }
                // The router counter is 0 now (freshly added or reset), so the next
                // usage poll measures the delta from zero.
                v.setRouterUptimeSeconds(0);
                voucherRepository.save(v);
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik reconcile failed: " + e.getMessage(), e);
        }
        if (restored > 0) {
            log.info("Reconcile re-created {} hotspot user(s) on {} after it came back online",
                    restored, router.getName());
        }
        return restored;
    }

    public void removeVoucher(Voucher voucher) {
        Router router = defaultRouter();
        if (!live(router)) {
            return;
        }
        try (ApiConnection connection = login(router)) {
            try {
                connection.execute("/ip/hotspot/active/remove [find user=" + voucher.getCode() + "]");
            } catch (Exception noActiveSession) {
                log.debug("No active session to kick for {}", voucher.getCode());
            }
            connection.execute("/ip/hotspot/user/remove [find name=" + voucher.getCode() + "]");
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Removed hotspot user for voucher {}", voucher.getCode());
    }

    /**
     * Returns the hotspot profile for the plan. An admin-set profile name
     * wins; otherwise a profile is auto-managed per plan carrying the
     * bandwidth rate limit and the shared-users device cap.
     */
    private String ensureHotspotProfile(ApiConnection connection, Plan plan) throws Exception {
        if (plan.getMikrotikProfile() != null && !plan.getMikrotikProfile().isBlank()) {
            return plan.getMikrotikProfile();
        }
        String name = "spa-plan-" + plan.getId();
        // getRateLimitString() appends the burst triple when the plan has one;
        // RouterOS takes it as a single space-separated value.
        String rate = plan.getRateLimitString();
        String rateLimit = rate != null ? " rate-limit=\"" + rate + "\"" : "";
        int sharedUsers = plan.getEffectiveMaxDevices();
        try {
            connection.execute(String.format(
                    "/ip/hotspot/user/profile/add name=%s shared-users=%d%s", name, sharedUsers, rateLimit));
        } catch (Exception alreadyExists) {
            connection.execute(String.format(
                    "/ip/hotspot/user/profile/set [find name=%s] shared-users=%d%s", name, sharedUsers, rateLimit));
        }
        return name;
    }

    // --- MAC binding ---

    /**
     * Locks each voucher to the first device that uses it. Reads active
     * hotspot sessions and writes the session MAC onto any user that has
     * none yet; RouterOS then rejects other devices.
     */
    @Transactional
    public void syncMacBindings() {
        MikrotikSettings s = settings();
        Router router = defaultRouter();
        if (!live(router) || !s.isMacBindingEnabled()) {
            return;
        }
        try (ApiConnection connection = login(router)) {
            Map<String, String> userMacs = new HashMap<>();
            for (Map<String, String> user : connection.execute("/ip/hotspot/user/print")) {
                userMacs.put(user.get("name"), user.getOrDefault("mac-address", ""));
            }
            for (Map<String, String> session : connection.execute("/ip/hotspot/active/print")) {
                String user = session.get("user");
                String mac = session.get("mac-address");
                if (user == null || mac == null || mac.isBlank()) {
                    continue;
                }
                String existing = userMacs.get(user);
                if (existing == null || !existing.isBlank()) {
                    continue;
                }
                connection.execute(String.format(
                        "/ip/hotspot/user/set [find name=%s] mac-address=%s", user, mac));
                voucherRepository.findByCode(user).ifPresent(v -> {
                    v.setBoundMac(mac);
                    voucherRepository.save(v);
                });
                log.info("MAC-bound voucher {} to {}", user, mac);
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void unbindVoucher(Voucher voucher) {
        Router router = defaultRouter();
        if (live(router)) {
            try (ApiConnection connection = login(router)) {
                connection.execute(String.format(
                        "/ip/hotspot/user/unset [find name=%s] value-name=mac-address", voucher.getCode()));
            } catch (Exception e) {
                throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
            }
        }
        voucher.setBoundMac(null);
    }

    // --- PPPoE subscribers ---

    public void provisionPppoe(Subscriber sub) {
        Router router = routerFor(sub.getRouterId());
        if (!live(router)) {
            log.info("MikroTik disabled — skipping PPPoE provisioning for {}", sub.getPppoeUsername());
            return;
        }
        try (ApiConnection connection = login(router)) {
            String profile = "spa-ppp-" + sub.getId();
            String rateLimit = sub.getBandwidth() != null && !sub.getBandwidth().isBlank()
                    ? " rate-limit=" + sub.getBandwidth() : "";
            try {
                connection.execute(String.format("/ppp/profile/add name=%s%s", profile, rateLimit));
            } catch (Exception exists) {
                connection.execute(String.format("/ppp/profile/set [find name=%s]%s", profile, rateLimit));
            }
            try {
                connection.execute(String.format(
                        "/ppp/secret/add name=%s password=%s service=pppoe profile=%s",
                        sub.getPppoeUsername(), sub.getPppoePassword(), profile));
            } catch (Exception exists) {
                connection.execute(String.format(
                        "/ppp/secret/set [find name=%s] password=%s profile=%s disabled=no",
                        sub.getPppoeUsername(), sub.getPppoePassword(), profile));
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Provisioned PPPoE secret for {} on {}", sub.getPppoeUsername(), router.getName());
    }

    public void setPppoeEnabled(Subscriber sub, boolean enabled) {
        Router router = routerFor(sub.getRouterId());
        if (!live(router)) {
            return;
        }
        String username = sub.getPppoeUsername();
        try (ApiConnection connection = login(router)) {
            connection.execute(String.format(
                    "/ppp/secret/set [find name=%s] disabled=%s", username, enabled ? "no" : "yes"));
            if (!enabled) {
                try {
                    connection.execute("/ppp/active/remove [find name=" + username + "]");
                } catch (Exception noSession) {
                    log.debug("No live PPPoE session to drop for {}", username);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("PPPoE {} {}", username, enabled ? "enabled" : "disabled");
    }

    public void removePppoe(Subscriber sub) {
        Router router = routerFor(sub.getRouterId());
        if (!live(router)) {
            return;
        }
        String username = sub.getPppoeUsername();
        try (ApiConnection connection = login(router)) {
            try {
                connection.execute("/ppp/active/remove [find name=" + username + "]");
            } catch (Exception noSession) {
                log.debug("No live PPPoE session to drop for {}", username);
            }
            connection.execute("/ppp/secret/remove [find name=" + username + "]");
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    // --- Monitoring & usage ---

    /** Router identity, uptime and session counts for the monitor job. */
    public Map<String, Object> probe(Router router) {
        Map<String, Object> out = new HashMap<>();
        try (ApiConnection connection = login(router)) {
            for (Map<String, String> res : connection.execute("/system/resource/print")) {
                out.put("uptime", res.get("uptime"));
                out.put("version", res.get("version"));
                out.put("boardName", res.get("board-name"));
                out.put("cpuLoad", res.get("cpu-load"));
                out.put("freeMemory", res.get("free-memory"));
                out.put("totalMemory", res.get("total-memory"));
            }
            out.put("hotspotUsers", connection.execute("/ip/hotspot/active/print").size());
            out.put("pppoeUsers", connection.execute("/ppp/active/print").size());
            out.put("online", true);
        } catch (Exception e) {
            out.put("online", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /**
     * Live sessions with bytes transferred, used for usage tracking.
     * Keys: user, address, macAddress, uptime, bytesIn, bytesOut, kind.
     */
    public List<Map<String, String>> activeSessions(Router router) {
        List<Map<String, String>> sessions = new java.util.ArrayList<>();
        if (!live(router)) {
            return sessions;
        }
        try (ApiConnection connection = login(router)) {
            for (Map<String, String> s : connection.execute("/ip/hotspot/active/print")) {
                sessions.add(Map.of(
                        "kind", "hotspot",
                        "user", s.getOrDefault("user", ""),
                        "address", s.getOrDefault("address", ""),
                        "macAddress", s.getOrDefault("mac-address", ""),
                        "uptime", s.getOrDefault("uptime", ""),
                        "bytesIn", s.getOrDefault("bytes-in", "0"),
                        "bytesOut", s.getOrDefault("bytes-out", "0")));
            }
            for (Map<String, String> s : connection.execute("/ppp/active/print")) {
                sessions.add(Map.of(
                        "kind", "pppoe",
                        "user", s.getOrDefault("name", ""),
                        "address", s.getOrDefault("address", ""),
                        "macAddress", s.getOrDefault("caller-id", ""),
                        "uptime", s.getOrDefault("uptime", ""),
                        "bytesIn", "0",
                        "bytesOut", "0"));
            }
        } catch (Exception e) {
            log.warn("Could not read sessions from {}: {}", router.getName(), e.getMessage());
        }
        return sessions;
    }

    /** Kicks one live session off the router; kind is "hotspot" or "pppoe". */
    public void disconnectSession(Router router, String user, String kind) {
        if (!live(router)) {
            throw new IllegalStateException("MikroTik integration is disabled");
        }
        try (ApiConnection connection = login(router)) {
            String command = "pppoe".equalsIgnoreCase(kind)
                    ? "/ppp/active/remove [find name=" + user + "]"
                    : "/ip/hotspot/active/remove [find user=" + user + "]";
            connection.execute(command);
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Disconnected {} ({}) from {}", user, kind, router.getName());
    }

    /**
     * Sets a hotspot user's rate-limit, e.g. "2M/1M" — used to throttle a pass
     * that has crossed its fair-use cap. A blank rate clears the limit. Also
     * kicks the live session so the new limit takes effect on reconnect.
     */
    public void setHotspotRate(Router router, String user, String rate) {
        if (!live(router)) {
            throw new IllegalStateException("MikroTik integration is disabled");
        }
        try (ApiConnection connection = login(router)) {
            String limit = rate == null ? "" : rate.trim();
            connection.execute("/ip/hotspot/user/set [find name=" + user + "] rate-limit=" + limit);
            connection.execute("/ip/hotspot/active/remove [find user=" + user + "]");
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Throttled hotspot user {} to '{}' on {}", user, rate, router.getName());
    }

    /** RouterOS uptime like "1w2d3h4m5s", "6h31m8s" or "45s" → seconds. */
    public static long parseUptime(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        long total = 0;
        long num = 0;
        for (char c : raw.trim().toCharArray()) {
            if (c >= '0' && c <= '9') {
                num = num * 10 + (c - '0');
            } else {
                long mult = switch (c) {
                    case 'w' -> 604_800L;
                    case 'd' -> 86_400L;
                    case 'h' -> 3_600L;
                    case 'm' -> 60L;
                    case 's' -> 1L;
                    default -> 0L;
                };
                total += num * mult;
                num = 0;
            }
        }
        return total;
    }

    /** Parses RouterOS byte counters, which may be "123" or "123/456". */
    public static BigDecimal parseBytes(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        String value = raw.contains("/") ? raw.split("/")[0] : raw;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
