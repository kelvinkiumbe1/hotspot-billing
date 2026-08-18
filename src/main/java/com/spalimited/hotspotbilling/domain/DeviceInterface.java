package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One port on a monitored device.
 *
 * <p>This is where the useful signal lives. That a switch answers a poll says
 * almost nothing; that its uplink has quietly renegotiated from 1G to 100M, or
 * that one port's error counter has climbed every poll for a week, is the fault
 * that becomes an outage on Friday night.
 */
@Entity
@Table(name = "device_interfaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceInterface {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deviceId;

    /** The device's own index for this port; stable while it stays up. */
    @Column(nullable = false)
    private Integer ifIndex;

    @Column(length = 120)
    private String ifName;

    /**
     * What a human typed on the switch to say what this port is for — "uplink
     * to core", "AP roof west". Worth more than every number here put together
     * when something breaks at 2am.
     */
    @Column(length = 255)
    private String ifAlias;

    @Column(length = 255)
    private String ifDescr;

    /** Whether the port is switched on, as opposed to whether it has a link. */
    @Builder.Default
    @Column(nullable = false)
    private boolean adminUp = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean operUp = false;

    /** Negotiated speed in bits per second, as the device reports it. */
    private Long speedBps;

    private Instant lastChangeAt;

    // --- Counters. Cumulative on the device, so only deltas mean anything. ---

    private Long lastInOctets;

    private Long lastOutOctets;

    private Long lastInErrors;

    private Long lastOutErrors;

    private Instant countersAt;

    private Long inBps;

    private Long outBps;

    /**
     * Errors since the last poll rather than since the device booted. A
     * lifetime total of 40,000 on a switch up for three years is noise; forty
     * in the last five minutes is a cable about to fail.
     */
    @Builder.Default
    @Column(nullable = false)
    private long inErrorsDelta = 0;

    @Builder.Default
    @Column(nullable = false)
    private long outErrorsDelta = 0;

    /**
     * Whether losing this port should raise the alarm. Off by default: paging
     * an operator for every unused access port is how they learn to ignore the
     * alerts that matter.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean monitored = false;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = Instant.now();
    }

    /** How full the link is right now, 0–100, or null when the speed is unknown. */
    @Transient
    public Integer getUtilisationPercent() {
        if (speedBps == null || speedBps <= 0 || inBps == null || outBps == null) {
            return null;
        }
        // Full duplex: the busier direction is what saturates, not the sum.
        long busier = Math.max(inBps, outBps);
        return (int) Math.min(100, busier * 100 / speedBps);
    }

    /** A human label for the port, preferring whatever a person wrote on it. */
    @Transient
    public String getLabel() {
        if (ifAlias != null && !ifAlias.isBlank()) {
            return ifAlias;
        }
        if (ifName != null && !ifName.isBlank()) {
            return ifName;
        }
        return ifDescr != null && !ifDescr.isBlank() ? ifDescr : "port " + ifIndex;
    }
}
