package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A single-use access credential. The voucher code doubles as the
 * MikroTik hotspot username/password.
 */
@Entity
@Table(name = "vouchers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voucher {

    public enum Status { UNUSED, ACTIVE, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private Status status = Status.UNUSED;

    /** Phone number of the buyer, if sold via M-Pesa. */
    private String phoneNumber;

    /**
     * For pay-per-minute custom passes: the exact minutes bought. When set
     * it overrides the plan's duration.
     */
    private Integer customDurationMinutes;

    /** MAC address of the first device that used this voucher, when MAC binding is on. */
    private String boundMac;

    /** Username of the admin/technician who generated it; null for customer purchases. */
    private String createdBy;

    /** The printed batch this voucher belongs to, if it came from one. */
    private Long batchId;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant activatedAt;

    private Instant expiresAt;

    /**
     * Connect-time used so far, in seconds — the app's authoritative total, so
     * a router that reboots and loses its own counter can be handed back the
     * customer's *remaining* time rather than a fresh pass or a lockout.
     */
    @Builder.Default
    @Column(nullable = false)
    private long usedSeconds = 0;

    /**
     * The last uptime counter read from the router for this user. Kept so each
     * poll adds only the delta since the previous one, and so a counter that
     * has reset (reboot, or our own reconcile) is detected and handled.
     */
    @Builder.Default
    @Column(nullable = false)
    private long routerUptimeSeconds = 0;

    /**
     * Last cumulative byte counters read from the router for this user, kept
     * (like {@link #routerUptimeSeconds}) so each traffic poll records only
     * the delta and copes with the counter resetting on reboot.
     */
    @Builder.Default
    @Column(nullable = false)
    private long lastBytesIn = 0;

    @Builder.Default
    @Column(nullable = false)
    private long lastBytesOut = 0;

    /** The router this voucher was last seen active on; null until observed. */
    private Long routerId;

    /** The duration this voucher actually grants (custom minutes if set, else the plan's). */
    @Transient
    public int getEffectiveDurationMinutes() {
        return customDurationMinutes != null ? customDurationMinutes : plan.getDurationMinutes();
    }

    @Transient
    public long getDurationSeconds() {
        return getEffectiveDurationMinutes() * 60L;
    }

    /** Connect-time left before the pass is spent, in seconds (never negative). */
    @Transient
    public long getRemainingSeconds() {
        return Math.max(0, getDurationSeconds() - usedSeconds);
    }

    @Transient
    public boolean isExhausted() {
        return getRemainingSeconds() <= 0;
    }
}
