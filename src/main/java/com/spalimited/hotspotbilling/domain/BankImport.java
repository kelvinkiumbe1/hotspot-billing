package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One uploaded bank statement. See V73__bank_statements.sql. */
@Entity
@Table(name = "bank_imports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "uploaded_by", length = 120)
    private String uploadedBy;

    @Column(name = "bank_name", length = 120)
    private String bankName;

    @Builder.Default
    @Column(name = "row_count", nullable = false)
    private int rowCount = 0;

    @Builder.Default
    @Column(name = "credit_count", nullable = false)
    private int creditCount = 0;

    @Builder.Default
    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount = 0;

    @Builder.Default
    @Column(name = "matched_count", nullable = false)
    private int matchedCount = 0;

    @Builder.Default
    @Column(name = "applied_count", nullable = false)
    private int appliedCount = 0;
}
