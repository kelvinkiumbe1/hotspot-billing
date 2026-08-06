package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A reseller who takes voucher stock and sells it on, earning commission.
 * Batches are assigned to an agent; sales and commission are derived from
 * the vouchers in those batches that customers have actually used.
 */
@Entity
@Table(name = "agents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phoneNumber;

    /** Short code printed on their batches, e.g. "AG01". */
    @Column(nullable = false, unique = true)
    private String code;

    /** Share of face value the agent keeps, as a percentage. */
    @Builder.Default
    @Column(nullable = false)
    private int commissionPercent = 10;

    private String location;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Commission already settled with the agent. */
    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal commissionPaid = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
