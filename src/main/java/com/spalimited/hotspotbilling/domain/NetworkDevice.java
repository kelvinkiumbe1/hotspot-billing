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

    public enum Kind { SWITCH, ACCESS_POINT, ONT, UPS, SERVER, ROUTER, OLT, OTHER }

    /**
     * Which vendor's ONU tables to walk on an OLT.
     *
     * <p>Only meaningful when the kind is OLT. GPON ONU tables live in enterprise
     * MIBs with nothing in common between vendors, so this picks a set of columns
     * rather than describing the hardware -- see OltProfile, and the four
     * overrides below for when a preset turns out to be wrong.
     */
    public enum OltVendor { HUAWEI, ZTE, VSOL, BDCOM, FIBERHOME }

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

    // --- OLT: where to find the ONUs hanging off it ---
    //
    // Every one of these is null on anything that is not an OLT, and null on an
    // OLT means "use the vendor preset". They exist because the presets cannot be
    // verified without the hardware: an operator with snmpwalk and their own OLT
    // can find the right column and fix it here, rather than waiting for a
    // release and a guess that might be wrong again.

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OltVendor oltVendor;

    /** The column carrying each ONU's serial or MAC. */
    @Column(length = 120)
    private String onuSerialOid;

    /** Receive power at the ONU, which is the reading anybody actually wants. */
    @Column(length = 120)
    private String onuRxPowerOid;

    @Column(length = 120)
    private String onuTxPowerOid;

    @Column(length = 120)
    private String onuStatusOid;

    /** How the vendor encodes the number: fixed-point dBm, or raw microwatts. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private com.spalimited.hotspotbilling.service.snmp.OpticalPower.Unit onuPowerUnit;

    /** The divisor for a fixed-point reading -- 100 for hundredths of a dBm. */
    private Double onuPowerScale;

    // --- OLT: the command line, for provisioning ---
    //
    // Telnet, because these boxes offer it and no SSH client is available to this
    // build. Which means these credentials cross the management network in the
    // clear -- a real thing to know rather than a detail, and one more reason an
    // OLT belongs on an isolated management VLAN. The admin says so where the
    // password is entered.

    @Column(length = 120)
    private String cliUsername;

    @Column(length = 120)
    private String cliPassword;

    /** Telnet unless told otherwise. */
    private Integer cliPort;

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
