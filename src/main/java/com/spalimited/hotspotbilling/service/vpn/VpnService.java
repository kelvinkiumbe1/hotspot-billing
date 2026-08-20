package com.spalimited.hotspotbilling.service.vpn;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.VpnSettings;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.VpnSettingsRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MikrotikService;
import com.spalimited.hotspotbilling.service.ipam.Cidr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tunnel each router dials out to, so it can be reached behind carrier NAT.
 *
 * <p>This is the quiet reason half the network features only half worked. A site
 * router on a mobile or domestic line has no address anything can dial in to, so
 * the API could not be opened, a TR-069 connection request could not be
 * delivered, and every "apply now" degraded to "apply whenever the box next
 * checks in" -- while the monitor reported it offline the whole time.
 *
 * <h2>What this owns, and what it does not</h2>
 *
 * <p>It does not run a VPN server. That is one WireGuard interface on the host,
 * written by deploy/vpn-setup.sh. What it owns is the part that is genuinely
 * application state: which router has which tunnel address, what its public key
 * is, and whether the tunnel has ever actually carried a connection.
 *
 * <h2>No private key exists in this system</h2>
 *
 * <p>RouterOS generates its own private key when the interface is created and
 * this reads back only the public half; the server's key is made once with
 * {@code wg genkey} at deploy time. So there is no private key in the database,
 * none in a backup, and none in a log -- a dump of this system cannot impersonate
 * a peer on the tunnel. That is worth more than the convenience of generating
 * keys here would have been.
 *
 * <h2>The one manual step, and why it stays manual</h2>
 *
 * <p>After a router is configured, its public key has to be added to the
 * server's peer list. This produces the exact stanza but does not apply it,
 * because doing so means this process editing the host's own network
 * configuration and reloading an interface every other tenant is also using. A
 * line to paste is the right size of blast radius.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VpnService {

    private final VpnSettingsRepository settingsRepo;
    private final RouterRepository routers;
    private final MikrotikService mikrotikService;
    private final AuditService audit;

    @Transactional
    public VpnSettings settings() {
        return settingsRepo.findById(VpnSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(VpnSettings.builder()
                        .id(VpnSettings.SINGLETON_ID).build()));
    }

    @Transactional
    public VpnSettings save(VpnSettings incoming, String who) {
        VpnSettings current = settings();
        current.setEnabled(incoming.isEnabled());
        current.setServerPublicKey(blank(incoming.getServerPublicKey()));
        current.setEndpoint(blank(incoming.getEndpoint()));
        if (incoming.getSubnet() != null && !incoming.getSubnet().isBlank()) {
            // Parsed here so a typo is refused now rather than producing an
            // address nothing can route to.
            Cidr.parse(incoming.getSubnet().trim());
            current.setSubnet(incoming.getSubnet().trim());
        }
        if (incoming.getServerAddress() != null && !incoming.getServerAddress().isBlank()) {
            current.setServerAddress(incoming.getServerAddress().trim());
        }
        current.setKeepaliveSeconds(Math.max(10, Math.min(180, incoming.getKeepaliveSeconds())));
        if (incoming.getInterfaceName() != null && !incoming.getInterfaceName().isBlank()) {
            current.setInterfaceName(incoming.getInterfaceName().trim());
        }
        current.setUpdatedAt(Instant.now());
        current.setUpdatedBy(who);
        return settingsRepo.save(current);
    }

    /** Why the tunnel cannot be used yet, or null if it can. */
    public String whyNotUsable() {
        VpnSettings cfg = settings();
        if (!cfg.isEnabled()) {
            return "The tunnel is switched off in settings.";
        }
        if (cfg.getServerPublicKey() == null || cfg.getServerPublicKey().isBlank()) {
            return "No server public key. Run deploy/vpn-setup.sh on the host and paste "
                    + "the public key it prints.";
        }
        if (cfg.getEndpoint() == null || cfg.getEndpoint().isBlank()) {
            return "No endpoint. Routers need a host:port they can dial from the outside.";
        }
        return null;
    }

    /**
     * The next free address in the tunnel subnet.
     *
     * <p>Skips the server's own address and anything already handed out. Sparse
     * on purpose: a router that is deleted and re-added gets a new address rather
     * than inheriting whatever the old one had, because the server's peer list
     * still contains the old pairing and reusing the address would mean two peers
     * claiming it.
     */
    @Transactional(readOnly = true)
    public String nextFreeAddress() {
        VpnSettings cfg = settings();
        Cidr range = Cidr.parse(cfg.getSubnet());
        Set<String> taken = new HashSet<>();
        taken.add(cfg.getServerAddress());
        for (Router r : routers.findAll()) {
            if (r.getVpnAddress() != null) {
                taken.add(r.getVpnAddress());
            }
        }
        // Walked rather than enumerated, so a wide subnet costs nothing: the
        // first free address is all anybody needs and it is usually the second.
        for (long a = range.firstUsable(); a <= range.lastUsable(); a++) {
            String candidate = Cidr.toAddress(a);
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("The tunnel subnet " + cfg.getSubnet()
                + " is full. Widen it in settings.");
    }

    /** What a configure attempt did, in words the screen shows unchanged. */
    public record Configured(boolean ok, String address, String publicKey,
                             String peerStanza, String message) {
    }

    /**
     * Puts a WireGuard interface on the router and reads its public key back.
     *
     * <p>Must be run while the router is still reachable the ordinary way -- this
     * is the step that creates the way in, so it cannot use it. On a router that
     * is already behind NAT and unreachable, {@link #script} produces the same
     * commands for somebody to paste at a console.
     */
    @Transactional
    public Configured configure(Long routerId, String who) {
        String blocked = whyNotUsable();
        if (blocked != null) {
            return new Configured(false, null, null, null, blocked);
        }
        Router router = routers.findById(routerId)
                .orElseThrow(() -> new IllegalArgumentException("No such router"));
        VpnSettings cfg = settings();

        // Keep an address the router already has: re-running this must not move a
        // router that is working, or the server's peer list points at the old one.
        String address = router.getVpnAddress() != null && !router.getVpnAddress().isBlank()
                ? router.getVpnAddress() : nextFreeAddress();

        try {
            String publicKey = mikrotikService.setupWireguard(
                    router, cfg.getInterfaceName(), address, Cidr.parse(cfg.getSubnet()).prefix(),
                    cfg.getServerPublicKey(), cfg.getEndpoint(), cfg.getServerAddress(),
                    cfg.getKeepaliveSeconds());

            router.setVpnAddress(address);
            router.setVpnPublicKey(publicKey);
            router.setVpnConfiguredAt(Instant.now());
            router.setVpnLastError(null);
            routers.save(router);

            audit.record(who, "vpn.configure",
                    router.getName() + " given tunnel address " + address);
            log.info("Configured tunnel on {} at {}", router.getName(), address);

            return new Configured(true, address, publicKey, peerStanza(router, cfg),
                    "Tunnel set up on " + router.getName() + " at " + address
                            + ". It will not come up until its peer line is added on the "
                            + "server -- copy the line below.");
        } catch (Exception e) {
            router.setVpnLastError(trim(e.getMessage()));
            routers.save(router);
            log.warn("Could not configure the tunnel on {}: {}", router.getName(), e.getMessage());
            return new Configured(false, null, null, null,
                    "Could not reach " + router.getName() + " to set it up: " + e.getMessage());
        }
    }

    /**
     * The line to add to the server's WireGuard configuration.
     *
     * <p>AllowedIPs is a single address with a /32, not the subnet. A peer
     * allowed the whole range would be permitted to send traffic claiming to be
     * any other router on the tunnel, which on a network of franchise partners is
     * the difference between a tunnel and a shared broadcast domain.
     */
    public String peerStanza(Router router, VpnSettings cfg) {
        if (router.getVpnPublicKey() == null || router.getVpnAddress() == null) {
            return null;
        }
        return "[Peer]\n"
                + "# " + router.getName() + "\n"
                + "PublicKey = " + router.getVpnPublicKey() + "\n"
                + "AllowedIPs = " + router.getVpnAddress() + "/32\n";
    }

    /** Every configured router's peer stanza, for building the server config once. */
    @Transactional(readOnly = true)
    public String allPeerStanzas() {
        VpnSettings cfg = settings();
        StringBuilder out = new StringBuilder();
        for (Router r : routers.findAll()) {
            String stanza = peerStanza(r, cfg);
            if (stanza != null) {
                out.append(stanza).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The same commands as {@link #configure}, as text to paste at a console.
     *
     * <p>For the router that is already unreachable, which is the one that needs
     * the tunnel most. It cannot be configured over the API precisely because it
     * has no way in yet.
     */
    @Transactional(readOnly = true)
    public List<String> script(Long routerId) {
        Router router = routers.findById(routerId)
                .orElseThrow(() -> new IllegalArgumentException("No such router"));
        VpnSettings cfg = settings();
        String address = router.getVpnAddress() != null ? router.getVpnAddress() : nextFreeAddress();
        return MikrotikService.wireguardCommands(cfg.getInterfaceName(), address,
                Cidr.parse(cfg.getSubnet()).prefix(), cfg.getServerPublicKey(),
                cfg.getEndpoint(), cfg.getServerAddress(), cfg.getKeepaliveSeconds());
    }

    /**
     * Tries the tunnel address specifically, and records what happened.
     *
     * <p>Deliberately not "can we reach this router" -- the ordinary path already
     * falls back to the public host, so a router can be perfectly reachable while
     * its tunnel is dead. This asks the narrower question, which is the one that
     * matters before somebody relies on it.
     */
    @Transactional
    public Map<String, Object> check(Long routerId) {
        Router router = routers.findById(routerId)
                .orElseThrow(() -> new IllegalArgumentException("No such router"));
        if (router.getVpnAddress() == null || router.getVpnAddress().isBlank()) {
            return Map.of("ok", false, "message", "This router has no tunnel address yet.");
        }
        boolean ok = mikrotikService.reachableAt(router, router.getVpnAddress());
        if (ok) {
            router.setVpnLastOkAt(Instant.now());
            router.setVpnLastError(null);
        } else {
            router.setVpnLastError("The tunnel address did not answer");
        }
        routers.save(router);
        return Map.of("ok", ok, "message", ok
                ? "The tunnel is up — " + router.getName() + " answered on "
                        + router.getVpnAddress() + "."
                : "No answer on " + router.getVpnAddress() + ". The commonest cause is the "
                        + "peer line not being added on the server yet.");
    }

    /** Every router and where its tunnel stands. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> overview() {
        VpnSettings cfg = settings();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Router r : routers.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routerId", r.getId());
            row.put("name", r.getName());
            row.put("host", r.getHost());
            row.put("vpnAddress", r.getVpnAddress());
            row.put("publicKey", r.getVpnPublicKey());
            row.put("configuredAt", r.getVpnConfiguredAt());
            row.put("lastOkAt", r.getVpnLastOkAt());
            row.put("error", r.getVpnLastError());
            // The state that catches the one manual step being skipped: set up on
            // the router, never seen working, which is what a missing peer line
            // looks like from here.
            row.put("awaitingPeer", r.getVpnPublicKey() != null && r.getVpnLastOkAt() == null);
            row.put("peerStanza", peerStanza(r, cfg));
            rows.add(row);
        }
        return rows;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String trim(String m) {
        if (m == null) {
            return "Unknown error";
        }
        return m.length() > 490 ? m.substring(0, 490) : m;
    }
}
