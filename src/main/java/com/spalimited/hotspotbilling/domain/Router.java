package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A MikroTik router / site. Multiple routers can be managed from one
 * dashboard; subscribers and vouchers are provisioned on the router they
 * belong to (or the default one).
 */
@Entity
@Table(name = "routers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Router {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Friendly name, e.g. "Westlands Site". */
    @Column(nullable = false, unique = true)
    private String name;

    private String location;

    @Column(nullable = false)
    private String host;

    @Builder.Default
    @Column(nullable = false)
    private int port = 8728;

    private String username;

    @JsonIgnore
    private String password;

    @Builder.Default
    @Column(nullable = false)
    private boolean useSsl = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** Used when a subscriber/voucher has no explicit router. */
    @Builder.Default
    @Column(nullable = false)
    private boolean defaultRouter = false;

    /** Branch/franchise this router belongs to; null means head office. */
    private Long branchId;

    // --- Live monitoring (filled by RouterMonitorJob) ---

    @Builder.Default
    @Column(nullable = false)
    private boolean online = false;

    private Instant lastSeenAt;

    private Instant lastCheckedAt;

    private String lastError;

    /**
     * When this router's configuration was last successfully copied off it, and
     * why the last attempt failed if it did.
     *
     * <p>Both live here rather than as a row per attempt because the question is
     * always "is this router being backed up" and never "what happened on the
     * night of the 4th". A null timestamp with no error means nobody has tried
     * yet; an old timestamp with an error is the case this exists to make
     * visible.
     */
    private Instant configBackupAt;

    @Column(length = 500)
    private String configBackupError;

    /**
     * The address this router answers on inside the WireGuard tunnel, or null if
     * it has never been set up for one.
     *
     * <p>Preferred over {@link #host} when opening a connection, because a router
     * behind carrier NAT has no reachable public address at all -- see
     * V77__vpn_reach.sql. Tried first and fallen back from, rather than trusted:
     * a tunnel that is down must not take the router with it.
     */
    @Column(name = "vpn_address", length = 64)
    private String vpnAddress;

    /**
     * The router's WireGuard public key, read back off the box after its
     * interface exists. The private half never leaves the router.
     *
     * <p>Until this is in the server's peer list the tunnel cannot come up, so a
     * router with a key and no successful connection is usually one whose peer
     * stanza has not been pasted in yet.
     */
    @Column(name = "vpn_public_key", length = 64)
    private String vpnPublicKey;

    @Column(name = "vpn_configured_at")
    private Instant vpnConfiguredAt;

    /**
     * Last time a connection over the tunnel actually worked -- our own
     * observation, not a handshake time read from WireGuard, which would require
     * the tunnel to be up in order to ask.
     */
    @Column(name = "vpn_last_ok_at")
    private Instant vpnLastOkAt;

    @Column(name = "vpn_last_error", length = 500)
    private String vpnLastError;

    /** RouterOS uptime string, e.g. "3w2d10:15:00". */
    private String uptime;

    private String routerOsVersion;

    private String boardName;

    /**
     * What this site's link can actually carry, in Mbps. Nothing can measure
     * this from the outside — it is what the operator bought — and without it
     * capacity planning has a numerator and no denominator.
     */
    private Integer capacityMbps;

    private Integer activeHotspotUsers;

    private Integer activePppoeUsers;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
