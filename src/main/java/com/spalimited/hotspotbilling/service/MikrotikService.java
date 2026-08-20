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
import com.spalimited.hotspotbilling.service.ipam.Cidr;
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

    /**
     * Opens the API, over the tunnel if there is one.
     *
     * <p>A router behind carrier NAT has no reachable public address, so its
     * tunnel address is the only way in -- see V77__vpn_reach.sql. That address
     * is tried FIRST and fallen back from rather than trusted: a tunnel that is
     * down for its own reasons must not take a router with it that is perfectly
     * reachable the ordinary way.
     *
     * <p>The order matters the other way round too. Trying the public host first
     * and the tunnel second would mean a router whose NAT happens to be
     * momentarily permissive answers on its public address, and the operator
     * never finds out their tunnel is broken until the day it is the only route.
     */
    private ApiConnection open(Router router) throws Exception {
        SocketFactory factory = router.isUseSsl() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        int port = router.getPort() > 0 ? router.getPort() : (router.isUseSsl() ? 8729 : 8728);

        List<String> addresses = new java.util.ArrayList<>();
        if (router.getVpnAddress() != null && !router.getVpnAddress().isBlank()) {
            addresses.add(router.getVpnAddress().trim());
        }
        if (router.getHost() != null && !router.getHost().isBlank()) {
            addresses.add(router.getHost().trim());
        }
        if (addresses.isEmpty()) {
            throw new IllegalStateException("No address to reach " + router.getName() + " on");
        }

        Exception last = null;
        for (int i = 0; i < addresses.size(); i++) {
            String address = addresses.get(i);
            try {
                ApiConnection connection = ApiConnection.connect(
                        factory, address, port, ApiConnection.DEFAULT_CONNECTION_TIMEOUT);
                if (i > 0) {
                    // Reached, but not the way we meant to. Worth a line: it is
                    // the only warning that a tunnel has stopped working.
                    log.info("Reached {} on {} after {} did not answer",
                            router.getName(), address, addresses.get(0));
                }
                return connection;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
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
        // With roaming on the pass goes to every site. Handled here rather than
        // at the three call sites in VoucherService, so a code sold, reissued or
        // renewed all behave the same way -- one of those paths being missed is
        // exactly how a customer ends up with a code that works in one shop.
        if (roamingOn()) {
            Pushed pushed = provisionVoucherEverywhere(voucher, limitMinutes);
            voucher.setPushedRouterIds(pushed.routerIds().isEmpty() ? null
                    : pushed.routerIds().stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(",")));
            if (!pushed.failures().isEmpty()) {
                log.warn("Voucher {} reached {} of {} routers: {}", voucher.getCode(),
                        pushed.routerIds().size(),
                        pushed.routerIds().size() + pushed.failures().size(),
                        String.join("; ", pushed.failures()));
            }
            return;
        }
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

    /**
     * Everything the router knows about one hotspot code, in one round trip.
     *
     * <p>Four questions in one call because they are asked together, by somebody
     * on the phone to a customer: is the code on this router, is anybody logged in
     * on it, is this device tied to a different pass, and does the plan's profile
     * exist. Four separate calls would each pay the login cost again.
     *
     * <p>Never throws for a missing thing -- absence is the answer. It throws only
     * when the router itself cannot be reached, which the caller reports as "and
     * nothing below could be checked".
     */
    public Map<String, Object> hotspotUserState(Router router, String code, String mac) {
        Map<String, Object> out = new HashMap<>();
        out.put("userExists", false);
        out.put("profileExists", false);
        try (ApiConnection connection = login(router)) {
            String profileWanted = null;
            for (Map<String, String> row : connection.execute(
                    "/ip/hotspot/user/print where name=" + code)) {
                out.put("userExists", true);
                profileWanted = row.get("profile");
                out.put("limitUptime", row.getOrDefault("limit-uptime", ""));
                out.put("disabled", row.getOrDefault("disabled", "false"));
            }

            for (Map<String, String> row : connection.execute(
                    "/ip/hotspot/active/print where user=" + code)) {
                out.put("activeMac", row.getOrDefault("mac-address", ""));
                out.put("activeAddress", row.getOrDefault("address", ""));
                out.put("activeUptime", row.getOrDefault("uptime", ""));
            }

            // A binding is the reason nobody can guess from the outside: the code
            // is fine, the router is fine, and the device is refused because it is
            // tied to a different pass.
            if (mac != null && !mac.isBlank()) {
                for (Map<String, String> row : connection.execute(
                        "/ip/hotspot/ip-binding/print where mac-address="
                                + mac.trim().toUpperCase(java.util.Locale.ROOT))) {
                    String comment = row.getOrDefault("comment", "");
                    String boundTo = comment.isBlank()
                            ? row.getOrDefault("address", "another pass") : comment;
                    if (!boundTo.equalsIgnoreCase(code)) {
                        out.put("macBoundTo", boundTo);
                    }
                }
            }

            if (profileWanted != null && !profileWanted.isBlank()) {
                out.put("profileExists", !connection.execute(
                        "/ip/hotspot/user/profile/print where name=" + profileWanted).isEmpty());
                out.put("profileName", profileWanted);
            } else {
                // No user means no profile to look for. Reported as present so a
                // missing user is not also reported as a missing profile -- one
                // problem, one line.
                out.put("profileExists", true);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    // --- Roaming: a pass that works at more than one site ---

    /**
     * Every router we are allowed to talk to, default first.
     *
     * <p>Default first so that with roaming off the behaviour is exactly what it
     * was, and with roaming on the site most likely to be used is provisioned
     * before the ones that might time out.
     */
    @Transactional(readOnly = true)
    public List<Router> manageableRouters() {
        List<Router> out = new java.util.ArrayList<>();
        Router preferred = null;
        try {
            preferred = defaultRouter();
        } catch (Exception none) {
            log.debug("No default router configured");
        }
        if (preferred != null && manageable(preferred)) {
            out.add(preferred);
        }
        for (Router r : routers.findAll()) {
            if (manageable(r) && (preferred == null || !r.getId().equals(preferred.getId()))) {
                out.add(r);
            }
        }
        return out;
    }

    /** Whether a pass should exist on every site rather than just one. */
    public boolean roamingOn() {
        return settings().isRoamingEnabled();
    }

    /** What a roaming push managed. */
    public record Pushed(List<Long> routerIds, List<String> failures) {
    }

    /**
     * Puts a hotspot user on every managed router.
     *
     * <p>Does not stop at the first failure. A router that is down must not
     * prevent the code working at the five that are up -- the customer standing
     * at one of those five is the person this is for. Which routers took it is
     * returned so the gap can be repaired later rather than being invisible.
     */
    public Pushed provisionVoucherEverywhere(Voucher voucher, int limitMinutes) {
        List<Long> done = new java.util.ArrayList<>();
        List<String> failures = new java.util.ArrayList<>();
        for (Router router : manageableRouters()) {
            try {
                pushVoucherTo(router, voucher, limitMinutes);
                done.add(router.getId());
            } catch (Exception e) {
                failures.add(router.getName() + ": " + e.getMessage());
                log.warn("Could not push voucher {} to {}: {}",
                        voucher.getCode(), router.getName(), e.getMessage());
            }
        }
        return new Pushed(done, failures);
    }

    /** One router, one voucher. Extracted so roaming and the single push share it. */
    public void pushVoucherTo(Router router, Voucher voucher, int limitMinutes) {
        if (!live(router)) {
            return;
        }
        String limitUptime = Math.max(1, limitMinutes) + "m";
        try (ApiConnection connection = login(router)) {
            String profile = ensureHotspotProfile(connection, voucher.getPlan());
            try {
                connection.execute(String.format(
                        "/ip/hotspot/user/add name=%s password=%s profile=%s limit-uptime=%s",
                        voucher.getCode(), voucher.getCode(), profile, limitUptime));
            } catch (Exception exists) {
                // Already there, from an earlier push or a repair sweep. Setting
                // rather than failing keeps the repair path idempotent.
                connection.execute(String.format(
                        "/ip/hotspot/user/set [find name=%s] password=%s profile=%s limit-uptime=%s",
                        voucher.getCode(), voucher.getCode(), profile, limitUptime));
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Removes a hotspot user from every managed router.
     *
     * <p>Best-effort per router and deliberately not transactional: a code left
     * behind on one box is a customer getting free minutes at one site, which is
     * bad, but refusing to remove it from the other five because one is down is
     * worse.
     */
    public List<String> removeVoucherEverywhere(String code) {
        List<String> failures = new java.util.ArrayList<>();
        for (Router router : manageableRouters()) {
            try (ApiConnection connection = login(router)) {
                connection.execute("/ip/hotspot/user/remove [find name=" + code + "]");
            } catch (Exception e) {
                failures.add(router.getName() + ": " + e.getMessage());
            }
        }
        return failures;
    }

    // --- Looking inside a router ---

    /**
     * The router's own log.
     *
     * <p>Worth having in the admin for one reason: when a customer cannot get
     * online, the answer is very often already written on the router -- a failed
     * PPPoE login naming the wrong password, a DHCP pool with nothing left, an
     * interface flapping. Today that means somebody opening WinBox, which means
     * somebody who has WinBox and the password, which means it does not happen.
     *
     * <p>Newest first, because the reason is nearly always the last thing that
     * happened.
     */
    public List<Map<String, String>> logs(Router router, String topicFilter, int limit) {
        if (!live(router)) {
            return List.of();
        }
        // RouterOS keeps the log in memory oldest-first and has no "tail". Asking
        // for everything and reversing here is the only option, so the limit is
        // applied after the read rather than saving anything on the wire.
        String command = "/log/print";
        if (topicFilter != null && !topicFilter.isBlank()) {
            // Matched on the router rather than here: the log can be tens of
            // thousands of lines on a busy box.
            command += " where topics~\"" + topicFilter.replace("\"", "") + "\"";
        }
        try (ApiConnection connection = login(router)) {
            List<Map<String, String>> rows = new java.util.ArrayList<>(connection.execute(command));
            java.util.Collections.reverse(rows);
            List<Map<String, String>> out = new java.util.ArrayList<>();
            for (Map<String, String> row : rows) {
                if (out.size() >= Math.max(1, Math.min(1000, limit))) {
                    break;
                }
                out.add(Map.of(
                        "time", row.getOrDefault("time", ""),
                        "topics", row.getOrDefault("topics", ""),
                        "message", row.getOrDefault("message", "")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wireless interfaces, on either of the two APIs RouterOS has.
     *
     * <p>RouterOS 6 and the older 7 builds use {@code /interface/wireless}; newer
     * 7 with wifiwave2 uses {@code /interface/wifi} and the field names differ.
     * Both are tried and the shape is normalised, because an operator should not
     * have to know which build their board shipped with -- and a mixed estate is
     * completely normal.
     */
    public List<Map<String, String>> wireless(Router router) {
        if (!live(router)) {
            return List.of();
        }
        try (ApiConnection connection = login(router)) {
            List<Map<String, String>> out = new java.util.ArrayList<>();
            for (String menu : List.of("/interface/wireless", "/interface/wifi")) {
                try {
                    for (Map<String, String> row : connection.execute(menu + "/print")) {
                        Map<String, String> n = new java.util.LinkedHashMap<>();
                        n.put("api", menu.endsWith("wifi") ? "wifi" : "wireless");
                        n.put("id", row.getOrDefault(".id", ""));
                        n.put("name", row.getOrDefault("name", ""));
                        n.put("ssid", row.getOrDefault("ssid", ""));
                        n.put("band", row.getOrDefault("band",
                                row.getOrDefault("channel.band", "")));
                        n.put("disabled", row.getOrDefault("disabled", "false"));
                        n.put("running", row.getOrDefault("running", ""));
                        n.put("macAddress", row.getOrDefault("mac-address", ""));
                        // The security profile, not the key. RouterOS will hand
                        // over a pre-shared key over the API and there is no
                        // reason for it to travel to a browser.
                        n.put("securityProfile", row.getOrDefault("security-profile",
                                row.getOrDefault("security", "")));
                        out.add(n);
                    }
                } catch (Exception notThisMenu) {
                    log.debug("{} not available on {}", menu, router.getName());
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Changes a wireless network's name, and optionally its password.
     *
     * <p>The password goes to the security profile the interface points at, which
     * is where RouterOS keeps it -- setting it on the interface silently does
     * nothing. A profile shared by two interfaces changes both, which is why the
     * profile name is reported back rather than assumed.
     */
    public String setWireless(Router router, String api, String name, String ssid,
                              String password) {
        if (!live(router)) {
            throw new IllegalStateException("MikroTik management is switched off");
        }
        String menu = "wifi".equals(api) ? "/interface/wifi" : "/interface/wireless";
        try (ApiConnection connection = login(router)) {
            if (ssid != null && !ssid.isBlank()) {
                connection.execute(menu + "/set [find name=" + name + "] ssid=\""
                        + ssid.replace("\"", "") + "\"");
            }
            if (password == null || password.isBlank()) {
                return null;
            }
            if ("wifi".equals(api)) {
                // wifiwave2 keeps the passphrase on the interface itself.
                connection.execute(menu + "/set [find name=" + name
                        + "] security.passphrase=\"" + password.replace("\"", "") + "\"");
                return "the interface";
            }
            // Classic wireless: find which profile this interface uses, then set
            // the key there.
            String profile = "default";
            for (Map<String, String> row : connection.execute(
                    menu + "/print where name=" + name)) {
                String p = row.get("security-profile");
                if (p != null && !p.isBlank()) {
                    profile = p;
                }
            }
            connection.execute("/interface/wireless/security-profiles/set [find name="
                    + profile + "] mode=dynamic-keys authentication-types=wpa2-psk"
                    + " wpa2-pre-shared-key=\"" + password.replace("\"", "") + "\"");
            return profile;
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /** Bridges and what is in them. */
    public Map<String, Object> bridges(Router router) {
        if (!live(router)) {
            return Map.of("bridges", List.of(), "ports", List.of());
        }
        try (ApiConnection connection = login(router)) {
            List<Map<String, String>> bridges = new java.util.ArrayList<>();
            for (Map<String, String> row : connection.execute("/interface/bridge/print")) {
                bridges.add(Map.of(
                        "name", row.getOrDefault("name", ""),
                        "protocolMode", row.getOrDefault("protocol-mode", ""),
                        "vlanFiltering", row.getOrDefault("vlan-filtering", "false"),
                        "disabled", row.getOrDefault("disabled", "false"),
                        "macAddress", row.getOrDefault("mac-address", "")));
            }
            List<Map<String, String>> ports = new java.util.ArrayList<>();
            for (Map<String, String> row : connection.execute("/interface/bridge/port/print")) {
                ports.add(Map.of(
                        "bridge", row.getOrDefault("bridge", ""),
                        "interfaceName", row.getOrDefault("interface", ""),
                        "pvid", row.getOrDefault("pvid", ""),
                        "disabled", row.getOrDefault("disabled", "false")));
            }
            return Map.of("bridges", bridges, "ports", ports);
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /** Every interface, with the counters -- the "is this port even up" question. */
    public List<Map<String, String>> interfaces(Router router) {
        if (!live(router)) {
            return List.of();
        }
        try (ApiConnection connection = login(router)) {
            List<Map<String, String>> out = new java.util.ArrayList<>();
            for (Map<String, String> row : connection.execute("/interface/print")) {
                out.add(Map.of(
                        "name", row.getOrDefault("name", ""),
                        "type", row.getOrDefault("type", ""),
                        "running", row.getOrDefault("running", "false"),
                        "disabled", row.getOrDefault("disabled", "false"),
                        "rxByte", row.getOrDefault("rx-byte", "0"),
                        "txByte", row.getOrDefault("tx-byte", "0"),
                        "comment", row.getOrDefault("comment", "")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    // --- WireGuard, so a router behind NAT can be reached ---

    /**
     * The commands that put a WireGuard tunnel on a router.
     *
     * <p>Static and returned as text, so the same sequence can be sent over the
     * API or handed to somebody to paste at a console. The router that most needs
     * a tunnel is the one already unreachable, and that one cannot be configured
     * over the API by definition.
     *
     * <p>No private key appears here. RouterOS generates its own when the
     * interface is added and we only ever read the public half back.
     *
     * <p>AllowedIPs on the router side is the whole tunnel subnet, which is the
     * opposite of the server side, where each peer is pinned to a single /32. The
     * asymmetry is deliberate: the router should be able to reach anything on the
     * tunnel it is given a route to, while the server must not let one router
     * claim to be another.
     */
    public static List<String> wireguardCommands(String interfaceName, String address,
                                                 int prefix, String serverPublicKey,
                                                 String endpoint, String serverAddress,
                                                 int keepalive) {
        String host = endpoint;
        String port = "13231";
        int colon = endpoint.lastIndexOf(':');
        if (colon > 0) {
            host = endpoint.substring(0, colon);
            port = endpoint.substring(colon + 1);
        }
        // The subnet the tunnel covers, derived from our own address so the
        // caller does not have to pass it twice.
        String subnet = Cidr.toAddress(Cidr.parse(serverAddress + "/" + prefix).networkAddress())
                + "/" + prefix;

        return List.of(
                // add-or-set, so running this twice does not leave two interfaces.
                "/interface/wireguard/add name=" + interfaceName
                        + " listen-port=0 comment=\"Zidi management tunnel\"",
                "/ip/address/add address=" + address + "/" + prefix
                        + " interface=" + interfaceName,
                "/interface/wireguard/peers/add interface=" + interfaceName
                        + " public-key=\"" + serverPublicKey + "\""
                        + " endpoint-address=" + host
                        + " endpoint-port=" + port
                        + " allowed-address=" + subnet
                        // Both ends need this. The tunnel exists because of a NAT
                        // mapping the router's own outbound packet created, and
                        // that mapping expires in silence -- after which the
                        // tunnel still looks up from the router and is dead from
                        // our side, which is the worst of the failure modes.
                        + " persistent-keepalive=" + keepalive + "s");
    }

    /**
     * Sets the tunnel up on a router and returns its public key.
     *
     * <p>Idempotent by intent rather than by hope: the interface is looked for
     * before being added, and the peer is replaced rather than appended, so
     * running this twice on the same router leaves one of each. RouterOS would
     * otherwise happily accumulate duplicates and the second peer would quietly
     * shadow the first.
     */
    public String setupWireguard(Router router, String interfaceName, String address,
                                 int prefix, String serverPublicKey, String endpoint,
                                 String serverAddress, int keepalive) {
        if (!live(router)) {
            throw new IllegalStateException(
                    "MikroTik management is switched off, so the tunnel cannot be set up");
        }
        String host = endpoint;
        String port = "13231";
        int colon = endpoint.lastIndexOf(':');
        if (colon > 0) {
            host = endpoint.substring(0, colon);
            port = endpoint.substring(colon + 1);
        }
        String subnet = Cidr.toAddress(Cidr.parse(serverAddress + "/" + prefix).networkAddress())
                + "/" + prefix;

        try (ApiConnection connection = login(router)) {
            List<Map<String, String>> existing = connection.execute(
                    "/interface/wireguard/print where name=" + interfaceName);
            if (existing.isEmpty()) {
                connection.execute("/interface/wireguard/add name=" + interfaceName
                        + " listen-port=0 comment=\"Zidi management tunnel\"");
            }

            // The address, replaced rather than added: a second /24 on the same
            // interface is a routing problem that only shows up under load.
            try {
                connection.execute("/ip/address/remove [find interface=" + interfaceName + "]");
            } catch (Exception none) {
                log.debug("No existing tunnel address on {}", router.getName());
            }
            connection.execute("/ip/address/add address=" + address + "/" + prefix
                    + " interface=" + interfaceName);

            try {
                connection.execute("/interface/wireguard/peers/remove [find interface="
                        + interfaceName + "]");
            } catch (Exception none) {
                log.debug("No existing tunnel peer on {}", router.getName());
            }
            connection.execute("/interface/wireguard/peers/add interface=" + interfaceName
                    + " public-key=\"" + serverPublicKey + "\""
                    + " endpoint-address=" + host
                    + " endpoint-port=" + port
                    + " allowed-address=" + subnet
                    + " persistent-keepalive=" + keepalive + "s");

            // Read the public half back. This is the only thing we take from the
            // router, and the only thing the server needs.
            for (Map<String, String> row : connection.execute(
                    "/interface/wireguard/print where name=" + interfaceName)) {
                String key = row.get("public-key");
                if (key != null && !key.isBlank()) {
                    return key.trim();
                }
            }
            throw new IllegalStateException(
                    "The interface was created but did not report a public key. "
                            + "RouterOS 7 or newer is needed for WireGuard.");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Whether the router answers on one specific address.
     *
     * <p>Used to test the tunnel on its own. The ordinary path falls back to the
     * public host, so "can we reach this router" is a different and easier
     * question than "is the tunnel working", and only the second one is worth
     * asking before somebody relies on it.
     */
    public boolean reachableAt(Router router, String address) {
        if (!live(router)) {
            return false;
        }
        SocketFactory factory = router.isUseSsl() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        int port = router.getPort() > 0 ? router.getPort() : (router.isUseSsl() ? 8729 : 8728);
        try (ApiConnection connection = ApiConnection.connect(
                factory, address, port, ApiConnection.DEFAULT_CONNECTION_TIMEOUT)) {
            connection.login(router.getUsername(), router.getPassword());
            // Logged in, not merely connected: a TCP accept proves a listener,
            // and something else listening on 8728 would otherwise read as the
            // tunnel working.
            connection.execute("/system/identity/print");
            return true;
        } catch (Exception e) {
            log.debug("No answer from {} at {}: {}", router.getName(), address, e.getMessage());
            return false;
        }
    }

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
