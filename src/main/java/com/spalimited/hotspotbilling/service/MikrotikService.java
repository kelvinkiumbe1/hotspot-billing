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
    /**
     * Whether this router is one we are allowed to talk to at all -- the global
     * MikroTik switch and the router's own. Public so a caller can leave a
     * router out rather than try it and record a failure that is really a
     * setting.
     */
    public boolean manageable(Router router) {
        return live(router);
    }

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
        provisionVoucher(voucher, voucher.getEffectiveDurationMinutes());
    }

    /**
     * As above, but with the connect-time to allow stated explicitly. Used when
     * a pass is reissued under a new code: the replacement must carry only what
     * is left of the original, not a fresh full allowance.
     */
    public void provisionVoucher(Voucher voucher, int limitMinutes) {
        Router router = defaultRouter();
        if (!live(router)) {
            log.info("MikroTik disabled — skipping provisioning of voucher {}", voucher.getCode());
            return;
        }
        String limitUptime = Math.max(1, limitMinutes) + "m";
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

    /**
     * Adds a hotspot user named after a device's MAC address. RouterOS logs
     * such a device in on its own — no code typed, no login page — provided
     * the hotspot server profile has {@code login-by=mac} switched on.
     *
     * <p>Used by zero-touch paybill activation to put the paying customer
     * online the moment their money lands. Best-effort by design: the caller
     * always sends the pass code as well, so a router without mac login (or
     * one that rejects this) costs the customer a code to type, not access.
     */
    public void provisionMacLogin(Router router, String mac, Plan plan, int minutes) {
        if (!live(router)) {
            log.info("MikroTik disabled — skipping MAC login for {}", mac);
            return;
        }
        String limitUptime = Math.max(1, minutes) + "m";
        try (ApiConnection connection = login(router)) {
            String profile = ensureHotspotProfile(connection, plan);
            try {
                connection.execute(String.format(
                        "/ip/hotspot/user/add name=%s mac-address=%s profile=%s limit-uptime=%s",
                        mac, mac, profile, limitUptime));
            } catch (Exception alreadyExists) {
                connection.execute(String.format(
                        "/ip/hotspot/user/set [find name=%s] mac-address=%s profile=%s limit-uptime=%s",
                        mac, mac, profile, limitUptime));
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Provisioned MAC login for {} on {}", mac, router.getName());
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
     * Every account configured on the router: {@code "hotspot"} → hotspot user
     * names, {@code "pppoe"} → PPP secret names, read in a single login.
     *
     * <p>This is what the router will let online, as opposed to what the
     * billing system sold — the revenue audit compares the two to catch access
     * created straight on the device, outside the system. It throws rather than
     * returning an empty result when the router can't be read, because "no
     * accounts" and "couldn't ask" mean very different things to that check.
     */
    public Map<String, List<String>> configuredAccounts(Router router) {
        Map<String, List<String>> out = new HashMap<>();
        out.put("hotspot", new java.util.ArrayList<>());
        out.put("pppoe", new java.util.ArrayList<>());
        if (!live(router)) {
            throw new IllegalStateException("MikroTik integration is disabled");
        }
        try (ApiConnection connection = login(router)) {
            for (Map<String, String> u : connection.execute("/ip/hotspot/user/print")) {
                String name = u.get("name");
                if (name != null && !name.isBlank()) {
                    out.get("hotspot").add(name);
                }
            }
            for (Map<String, String> s : connection.execute("/ppp/secret/print")) {
                String name = s.get("name");
                if (name != null && !name.isBlank()) {
                    out.get("pppoe").add(name);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
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

    /**
     * Drops whatever device is currently signed in on this code, leaving the
     * code itself valid. What a customer means by "log me out of the other
     * phone" — the pass survives, the session does not, and the remaining
     * time is untouched because the router counts uptime, not sessions.
     *
     * @return true when the router was reachable and the instruction landed
     */
    public boolean kickSessions(Voucher voucher) {
        Router router = defaultRouter();
        if (!live(router)) {
            return false;
        }
        String code = voucher.getCode();
        try (ApiConnection connection = login(router)) {
            // Three separate things hold a device on, and dropping only the
            // first is why "log me out" looks like it did nothing.
            quietly(connection, "/ip/hotspot/active/remove [find user=" + code + "]",
                    "no active session");
            // RouterOS's default server profile is login-by=cookie, and the
            // cookie outlives the session by days. Leave it and the browser
            // silently signs itself back in on its very next request — the
            // customer watches their "other device" reconnect in seconds.
            quietly(connection, "/ip/hotspot/cookie/remove [find user=" + code + "]",
                    "no stored cookie");
            // And if the code was bound to that device's MAC, the next device
            // would be refused — which is the opposite of what was asked for.
            quietly(connection, "/ip/hotspot/user/set [find name=" + code + "] mac-address=\"\"",
                    "no MAC binding");
            log.info("Released voucher {} from its device", code);
            return true;
        } catch (Exception e) {
            log.warn("Could not sign out {}: {}", code, e.getMessage());
            return false;
        }
    }

    /**
     * Runs a command whose "failure" is usually just nothing to do. Kept
     * separate so one absent cookie does not abandon the rest of the release.
     */
    private void quietly(ApiConnection connection, String command, String benignReason) {
        try {
            connection.execute(command);
        } catch (Exception e) {
            log.debug("{} ({}): {}", benignReason, command, e.getMessage());
        }
    }

    public void removeVoucher(Voucher voucher) {
        Router router = defaultRouter();
        if (!live(router)) {
            return;
        }
        try (ApiConnection connection = login(router)) {
            quietly(connection, "/ip/hotspot/active/remove [find user=" + voucher.getCode() + "]",
                    "no active session");
            // Removing the user makes a cookie re-login fail anyway, since it
            // names a user that no longer exists — but clearing it costs one
            // command and leaves nothing behind to reason about.
            quietly(connection, "/ip/hotspot/cookie/remove [find user=" + voucher.getCode() + "]",
                    "no stored cookie");
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
            // A static address, where IPAM has allocated one. On RouterOS this
            // is remote-address on the secret: PPPoE negotiates the address in
            // IPCP rather than over DHCP, so the customer's own router still
            // asks for one automatically and simply always gets the same one.
            //
            // Set here as well as over RADIUS because an operator not running
            // RADIUS would otherwise have IPAM assign an address that nothing
            // ever hands out — the allocation looks done and the customer still
            // gets a pool address.
            String remote = sub.getStaticIp() != null && !sub.getStaticIp().isBlank()
                    ? " remote-address=" + sub.getStaticIp().trim() : "";
            try {
                connection.execute(String.format(
                        "/ppp/secret/add name=%s password=%s service=pppoe profile=%s%s",
                        sub.getPppoeUsername(), sub.getPppoePassword(), profile, remote));
            } catch (Exception exists) {
                connection.execute(String.format(
                        "/ppp/secret/set [find name=%s] password=%s profile=%s disabled=no%s",
                        sub.getPppoeUsername(), sub.getPppoePassword(), profile, remote));
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Provisioned PPPoE secret for {} on {}", sub.getPppoeUsername(), router.getName());
    }

    /**
     * Changes the speed a PPPoE customer gets, by rewriting their own profile.
     *
     * <p>Every subscriber already has a profile to themselves -- spa-ppp-{id},
     * created in {@link #provisionPppoe} -- so this changes one customer without
     * touching anybody else on the router.
     *
     * <p>RouterOS applies a profile when the session starts, not when the profile
     * changes, so a customer already online keeps their old speed until they
     * reconnect. Dropping the session is therefore part of doing this, not an
     * extra: without it a throttle appears to have been applied and silently has
     * not been. The customer sees a few seconds offline while their router
     * redials, and the admin says so before anybody presses the button.
     *
     * @param rate a RouterOS rate-limit such as "2M/2M", or null to restore the
     *             subscriber's normal bandwidth
     */
    public void setPppoeRate(Subscriber sub, String rate) {
        Router router = routerFor(sub.getRouterId());
        if (!live(router)) {
            log.info("MikroTik disabled -- not changing the rate for {}", sub.getPppoeUsername());
            return;
        }
        String effective = rate != null && !rate.isBlank() ? rate.trim() : sub.getBandwidth();
        String profile = "spa-ppp-" + sub.getId();
        try (ApiConnection connection = login(router)) {
            String rateLimit = effective != null && !effective.isBlank()
                    ? " rate-limit=" + effective : " !rate-limit";
            connection.execute(String.format("/ppp/profile/set [find name=%s]%s", profile, rateLimit));
            try {
                // Forces the redial that makes the new profile take hold.
                connection.execute("/ppp/active/remove [find name=" + sub.getPppoeUsername() + "]");
            } catch (Exception notOnline) {
                // Offline already: they will pick the new rate up when they dial in.
                log.debug("No live PPPoE session to bounce for {}", sub.getPppoeUsername());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("PPPoE {} rate set to {}", sub.getPppoeUsername(),
                effective == null || effective.isBlank() ? "unlimited" : effective);
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

    // --- Configuration backup ---

    /**
     * What was captured, and how. The how matters: only one of these two can be
     * pasted back into a router.
     */
    public record ConfigExport(String method, String text) {
    }

    /**
     * Sections read one at a time when /export cannot be used.
     *
     * <p>Only prints. Nothing here changes anything on the router, so a backup
     * can never be the thing that broke the network.
     */
    private static final List<String> SECTIONS = List.of(
            "/system/identity", "/system/clock", "/system/ntp/client", "/system/scheduler",
            "/interface", "/interface/vlan", "/interface/bridge", "/interface/bridge/port",
            "/ip/address", "/ip/pool", "/ip/route", "/ip/dns",
            "/ip/dhcp-server", "/ip/dhcp-server/network",
            "/ip/firewall/filter", "/ip/firewall/nat", "/ip/firewall/mangle",
            "/ip/firewall/address-list",
            "/ppp/profile", "/ppp/secret",
            "/ip/hotspot", "/ip/hotspot/profile", "/ip/hotspot/user/profile",
            "/queue/simple", "/queue/tree", "/queue/type",
            "/radius", "/snmp", "/tool/netwatch", "/user");

    /**
     * A copy of the router's configuration.
     *
     * <p>Two ways, in order of how useful the result is.
     *
     * <p>{@code /export hide-sensitive} is the real thing: RouterOS renders its
     * whole configuration as commands that can be pasted back into a replacement
     * box. That is what makes this insurance rather than documentation.
     *
     * <p>If that returns nothing usable -- and it might, because what the API
     * gives back for /export differs between RouterOS versions and this has
     * never been run against real hardware -- the fallback reads the
     * configuration section by section with ordinary prints, whose shape is the
     * same one every other call in this class already relies on. That result is a
     * complete record of what was configured but is NOT a restore file, and
     * everything downstream of here says so rather than letting somebody discover
     * it on the night they need it.
     *
     * <p>hide-sensitive is deliberate. Without it the text contains every
     * customer's PPPoE password and the RADIUS shared secret, which would put
     * them in the database in the clear. Nothing is lost by redacting them:
     * Zidi is where those come from in the first place and can write them back.
     * What Zidi cannot regenerate is the hand-made half -- the firewall rules,
     * queues, VLANs and routes somebody built by hand -- and that is exactly
     * what this keeps.
     */
    public ConfigExport exportConfig(Router router) {
        try (ApiConnection connection = login(router)) {
            String exported = tryExport(connection);
            if (exported != null) {
                return new ConfigExport("EXPORT", exported);
            }
            return new ConfigExport("SECTIONS", readSections(connection));
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /** The real export, or null if this router did not give us one. */
    private String tryExport(ApiConnection connection) {
        try {
            List<Map<String, String>> rows = connection.execute("/export hide-sensitive");
            StringBuilder out = new StringBuilder();
            for (Map<String, String> row : rows) {
                if (row.size() == 1) {
                    // The usual shape: one value per line of the export.
                    out.append(row.values().iterator().next()).append('\n');
                } else {
                    // Anything else is rendered rather than dropped. A shape we
                    // did not expect is still the router's configuration, and
                    // guessing which keys matter would lose the ones that do.
                    for (Map.Entry<String, String> e : row.entrySet()) {
                        out.append(e.getKey()).append('=').append(e.getValue()).append(' ');
                    }
                    out.append('\n');
                }
            }
            String text = out.toString().strip();
            // A handful of characters is an error message or an empty reply, not
            // a configuration. Falling through to the sections is better than
            // storing that and calling it a backup.
            return text.length() > 200 ? text : null;
        } catch (Exception notSupported) {
            log.debug("/export unavailable, falling back to section reads: {}", notSupported.getMessage());
            return null;
        }
    }

    /** Section-by-section, using only prints. */
    private String readSections(ApiConnection connection) {
        StringBuilder out = new StringBuilder();
        out.append("# Read section by section because /export was not available.\n");
        out.append("# This is a record of the configuration, not a file you can paste back.\n");
        for (String section : SECTIONS) {
            List<Map<String, String>> rows;
            try {
                rows = connection.execute(section + "/print");
            } catch (Exception missing) {
                // Not every section exists on every board or licence level.
                continue;
            }
            if (rows.isEmpty()) {
                continue;
            }
            out.append('\n').append(section).append('\n');
            for (Map<String, String> row : rows) {
                for (Map.Entry<String, String> e : row.entrySet()) {
                    String key = e.getKey();
                    // .id changes when a rule is re-added and would otherwise make
                    // an untouched configuration look different every night.
                    if (".id".equals(key) || "password".equals(key) || "secret".equals(key)) {
                        continue;
                    }
                    out.append("    ").append(key).append('=').append(e.getValue()).append('\n');
                }
                out.append("    --\n");
            }
        }
        return out.toString();
    }

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
