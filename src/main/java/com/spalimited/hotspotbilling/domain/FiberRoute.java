package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A cable run between two nodes. Drawn as a straight line unless waypoints
 * are given, which is how a route follows a road rather than cutting across
 * the block.
 */
@Entity
@Table(name = "fiber_routes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FiberRoute {

    public enum Kind { BACKBONE, DISTRIBUTION, DROP }

    public enum Status { PLANNED, ACTIVE, FAULT, DECOMMISSIONED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.DISTRIBUTION;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PLANNED;

    @Column(nullable = false)
    private Long fromNodeId;

    @Column(nullable = false)
    private Long toNodeId;

    /** Fibre count in the cable. */
    private Integer cores;

    /** Route length along the ground, which is longer than the straight line. */
    private Integer lengthMeters;

    /**
     * Intermediate points as "lat,lng;lat,lng". Kept as text because the
     * database has no geometry type and the map only needs to draw them.
     */
    @Column(length = 4000)
    private String waypoints;

    @Column(length = 1000)
    private String notes;

    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
