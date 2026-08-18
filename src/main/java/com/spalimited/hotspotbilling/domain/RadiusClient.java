package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A router allowed to ask us whether someone may log in.
 *
 * <p>RADIUS has no certificates and no handshake — a shared secret folded into
 * MD5 is the whole of its authentication. So the list of who may ask is the
 * real security boundary, and an address that is not on it gets silence rather
 * than a rejection: a rejection would confirm the server is here.
 */
@Entity
@Table(name = "radius_clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadiusClient {

    /**
     * Which vendor's attributes to send back. Only affects the speed limit and
     * similar extras — getting it wrong costs the rate cap, not the login.
     */
    public enum Vendor { MIKROTIK, CISCO, UBIQUITI, CAMBIUM, RUCKUS, OMADA, GENERIC }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** A single address, or a CIDR block for a NAS pool. */
    @Column(nullable = false, unique = true, length = 64)
    private String address;

    @JsonIgnore
    @Column(nullable = false)
    private String sharedSecret;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Vendor vendor = Vendor.GENERIC;

    /** The router row this NAS corresponds to, when it is one we manage. */
    private Long routerId;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** Where to send a Disconnect-Request. 3799 is the RFC port; some use 1700. */
    @Builder.Default
    @Column(nullable = false)
    private int coaPort = 3799;

    private Instant lastRequestAt;

    @Builder.Default
    @Column(nullable = false)
    private long accepts = 0;

    @Builder.Default
    @Column(nullable = false)
    private long rejects = 0;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
