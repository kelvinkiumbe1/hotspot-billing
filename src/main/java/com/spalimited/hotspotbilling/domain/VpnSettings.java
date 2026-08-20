package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The tunnel routers dial out to, so they can be reached behind carrier NAT.
 * Single row (id = 1). See V77__vpn_reach.sql for why this exists at all.
 *
 * <p>No private key is stored anywhere in this system: RouterOS makes its own and
 * we read back the public half, and the server's key is made with {@code wg
 * genkey} at deploy time. A dump of this database cannot impersonate a peer.
 */
@Entity
@Table(name = "vpn_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VpnSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** The host's WireGuard public key. Public half only. */
    @Column(name = "server_public_key", length = 64)
    private String serverPublicKey;

    /** What the router dials -- host:port, reachable from the outside. */
    @Column(length = 255)
    private String endpoint;

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String subnet = "10.77.0.0/24";

    @Builder.Default
    @Column(name = "server_address", nullable = false, length = 64)
    private String serverAddress = "10.77.0.1";

    /**
     * Keepalive seconds. Needed because the whole scheme rests on a NAT mapping
     * the router's outbound packet created, and that mapping expires in silence.
     */
    @Builder.Default
    @Column(name = "keepalive_seconds", nullable = false)
    private int keepaliveSeconds = 25;

    @Builder.Default
    @Column(name = "interface_name", nullable = false, length = 64)
    private String interfaceName = "zidi-vpn";

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    /** Whether enough is filled in to configure a router at all. */
    @Transient
    public boolean usable() {
        return enabled
                && serverPublicKey != null && !serverPublicKey.isBlank()
                && endpoint != null && !endpoint.isBlank();
    }
}
