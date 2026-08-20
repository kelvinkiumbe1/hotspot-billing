package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One upload of somebody else's customer book.
 *
 * <p>A batch is staged, looked at, and either promoted into real customers or
 * thrown away. It is never edited: a corrected export is a new batch, so what
 * the operator approved is still on file afterwards.
 */
@Entity
@Table(name = "migration_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationBatch {

    public enum Status { STAGED, PROMOTED, DISCARDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private com.spalimited.hotspotbilling.service.migration.MigrationSource source;

    @Column(length = 200)
    private String label;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.STAGED;

    @Builder.Default
    @Column(name = "row_count", nullable = false)
    private int rowCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @Column(name = "promoted_by")
    private String promotedBy;

    @Column(length = 2000)
    private String notes;
}
