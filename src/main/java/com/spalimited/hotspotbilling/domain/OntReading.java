package com.spalimited.hotspotbilling.domain;

import com.spalimited.hotspotbilling.service.snmp.OpticalPower;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One ONU on one OLT, as last seen.
 *
 * <p>The current state rather than a history: one row per ONU, updated in place.
 * A full OLT is a couple of thousand ONUs and polling every five minutes would be
 * half a million rows a day for a question nobody asks — "what is this customer's
 * light right now" and "which drops are failing" are both answered by the latest
 * reading.
 *
 * <p>What is kept from the past is exactly one number: {@link #previousRxDbm}. It
 * is the difference between "this link has always been poor", which is a bad
 * install, and "this link was fine yesterday", which is a fibre somebody cut this
 * morning. Those need different vans.
 */
@Entity
@Table(name = "ont_readings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"olt_device_id", "serial"}),
        indexes = {
                @Index(name = "ont_readings_olt_idx", columnList = "olt_device_id"),
                @Index(name = "ont_readings_health_idx", columnList = "health"),
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OntReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The OLT this hangs off, as a NetworkDevice of kind OLT. */
    @Column(name = "olt_device_id", nullable = false)
    private Long oltDeviceId;

    /**
     * The ONU's serial or MAC, and the reason this table is keyed on it.
     *
     * <p>Not the SNMP table index. That moves when ONUs are added or removed, so
     * keying on it would quietly start attributing one customer's readings to
     * another after the first time somebody swapped a unit.
     */
    @Column(nullable = false, length = 64)
    private String serial;

    /** Whatever the OLT calls it, which is usually what the installer typed. */
    @Column(length = 160)
    private String description;

    /** The SNMP row this was last found at. Useful for a technician, not a key. */
    private Integer tableIndex;

    /** What the OLT says about the ONU: online, offline, dying-gasp, and so on. */
    @Column(length = 40)
    private String status;

    /** Receive power at the ONU, in dBm. Null means the OLT gave no reading. */
    private Double rxDbm;

    /** Transmit power, where the OLT reports it. */
    private Double txDbm;

    /**
     * The receive power before this poll.
     *
     * <p>Only the one previous value, which is all a drop needs. Kept on the row
     * rather than in a history table for the reason in the class comment.
     */
    private Double previousRxDbm;

    /** The band the current reading falls in, so a list can be sorted worst-first. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OpticalPower.Health health;

    /**
     * The customer this ONU serves, once somebody has said so.
     *
     * <p>Nullable and stays that way until matched. An OLT knows serial numbers
     * and a billing system knows customers, and nothing connects the two except a
     * person — so this is set by hand or by matching against equipment, never
     * guessed.
     */
    private Long subscriberId;

    /** When the reading was taken. */
    private Instant lastSeenAt;

    /** When a drop was last reported, so the same fault is not sent every poll. */
    private Instant lastAlertedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        if (lastSeenAt == null) {
            lastSeenAt = Instant.now();
        }
    }
}
