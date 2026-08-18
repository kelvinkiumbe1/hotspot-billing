package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A device on the network that is not a MikroTik: the switch in the cabinet,
 * the sector antenna on the mast, the ONT in a customer's house, the UPS.
 *
 * <p>These have always been invisible here, which matters because their
 * failures do not show up as a router going offline. The router stays perfectly
 * healthy while the switch behind it drops half its ports, and the first report
 * comes from a customer.
 *
 * <p>Polled over SNMP, which every one of them already speaks — nothing is
 * installed on the device, and nothing about it has to be a MikroTik.
 */
@Entity
@Table(name = "network_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NetworkDevice {

    public enum Kind { SWITCH, ACCESS_POINT, ONT, UPS, SERVER, ROUTER, OTHER }

    /**
     * v1 exists only because some very old gear answers nothing else. v2c is
     * what most switches are shipped with. v3 is the one that does not put a
     * password on the wire.
     */
    public enum Version { V1, V2C, V3 }

    /** SNMPv3 authentication digest. NONE means noAuthNoPriv. */
    public enum AuthProtocol { NONE, MD5, SHA1, SHA224, SHA256, SHA384, SHA512 }

    /** SNMPv3 payload encryption. NONE means the packet is readable in flight. */
    public enum PrivProtocol { NONE, DES, TRIPLE_DES, AES128, AES192, AES256 }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind = Kind.OTHER;

    @Column(nullable = false, length = 120)
    private String host;

    @Builder.Default
    @Column(nullable = false)
    private int port = 161;

    @Column(length = 160)
    private String location;

    /** Branch this device belongs to; null means head office. */
    private Long branchId;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    // --- Credentials. None of these ever leave the backend. ---

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "snmp_version", nullable = false, length = 8)
    private Version snmpVersion = Version.V2C;

    @JsonIgnore
    @Column(length = 120)
    private String community;

    /** The v3 username. Not itself a secret, but useless without the rest. */
    @Column(length = 120)
    private String securityName;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private AuthProtocol authProtocol;

    @JsonIgnore
    private String authPassphrase;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PrivProtocol privProtocol;

    @JsonIgnore
    private String privPassphrase;

    // --- Live state, rewritten every poll ---

    @Builder.Default
    @Column(nullable = false)
    private boolean online = false;

    private Instant lastSeenAt;

    private Instant lastCheckedAt;

    @Column(length = 500)
    private String lastError;

    /** The device's own name for itself, which is often not the one we gave it. */
    @Column(length = 255)
    private String sysName;

    @Column(length = 500)
    private String sysDescr;

    @Column(length = 255)
    private String sysLocation;

    @Column(length = 255)
    private String sysContact;

    /**
     * Seconds since the device booted. Worth storing because a device that
     * reboots every night looks perfectly healthy to a poller that only asks
     * whether it answers.
     */
    private Long uptimeSeconds;

    /** When it was last seen to have restarted — i.e. uptime went backwards. */
    private Instant lastRebootAt;

    @Column(length = 1000)
    private String notes;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** True when this device has enough credentials to be worth polling. */
    @Transient
    public boolean isConfigured() {
        if (host == null || host.isBlank()) {
            return false;
        }
        return snmpVersion == Version.V3
                ? securityName != null && !securityName.isBlank()
                : community != null && !community.isBlank();
    }

    /**
     * Whether the credentials on this device are readable by anyone who can see
     * the traffic. Surfaced rather than left implicit, because "we're on SNMP"
     * is usually said as though it meant one thing.
     */
    @Transient
    public boolean isCredentialInClear() {
        return snmpVersion != Version.V3
                || authProtocol == null || authProtocol == AuthProtocol.NONE;
    }
}
