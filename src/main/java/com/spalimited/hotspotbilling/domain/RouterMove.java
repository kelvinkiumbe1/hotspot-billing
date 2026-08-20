package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One attempt to move customers onto a different router.
 *
 * <p>Recorded because these half-fail: the new router accepts twelve of twenty
 * and then stops answering. Without a record the remaining eight are a
 * discrepancy somebody notices weeks later; with one they are a list.
 */
@Entity
@Table(name = "router_moves")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouterMove {

    /** TRANSFER moves a chosen batch; REPLACE moves everything off a dead box. */
    public enum Kind { TRANSFER, REPLACE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Kind kind;

    @Column(name = "from_router_id")
    private Long fromRouterId;

    @Column(name = "to_router_id", nullable = false)
    private Long toRouterId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Builder.Default
    @Column(name = "moved_count", nullable = false)
    private int movedCount = 0;

    @Builder.Default
    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    /** Who did not make it, and why. The reason this table exists. */
    @Column(length = 4000)
    private String detail;

    @Column(name = "started_by", length = 120)
    private String startedBy;
}
