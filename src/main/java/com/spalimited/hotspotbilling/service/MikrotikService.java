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
     * Ensures at least one router row exists, migrating the legacy
     * single-router settings into it, and returns the default router.
     */
    @Transactional
    public Router defaultRouter() {
        return routers.findFirstByDefaultRouterTrue()
                .or(() -> routers.findAllByOrderByNameAsc().stream().findFirst())
                .orElseGet(() -> {
                    MikrotikSettings s = settings();
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
        String rateLimit = plan.getBandwidth() != null && !plan.getBandwidth().isBlank()
                ? " rate-limit=" + plan.getBandwidth() : "";
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
