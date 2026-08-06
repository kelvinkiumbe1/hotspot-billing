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

    /** The duration this voucher actually grants (custom minutes if set, else the plan's). */
    @Transient
    public int getEffectiveDurationMinutes() {
        return customDurationMinutes != null ? customDurationMinutes : plan.getDurationMinutes();
    }
}
