package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A printed run of vouchers, kept as a record so stock can be traced:
 * who generated it, which plan it covers, and which agent holds it.
 */
@Entity
@Table(name = "voucher_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoucherBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-facing reference, e.g. "BATCH-000042". */
    @Column(nullable = false, unique = true)
    private String reference;

    @ManyToOne
    @JoinColumn(name = "plan_id")
    private Plan plan;

    /** Set instead of a plan duration for pay-per-minute batches. */
    private Integer customMinutes;

    @Column(nullable = false)
    private int count;

    private String prefix;

    private Integer codeLength;

    /** Agent holding this stock; null means head office. */
    private Long agentId;

    private String createdBy;

    @Column(length = 300)
    private String note;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
