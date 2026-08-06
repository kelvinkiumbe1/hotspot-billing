package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A point on the fibre plant: a POP, an OLT, a joint closure, a splitter,
 * a distribution box or a drop at a customer's wall. Everything physical
 * hangs off these, and routes join them together.
 */
@Entity
@Table(name = "fiber_nodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FiberNode {

    public enum Kind { POP, OLT, CLOSURE, SPLITTER, ODB, DROP }

    public enum Status { PLANNED, ACTIVE, FAULT, DECOMMISSIONED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PLANNED;

    /** Decimal degrees. Both are required — a node with no position cannot be drawn. */
    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** Total ports or splitter legs, where the kind has them. */
    private Integer capacity;

    /** How many of those are taken. */
    private Integer used;

    /** The upstream node this one hangs off, for tracing a fault to its source. */
    private Long parentId;

    /** Set on a DROP that terminates at a subscriber's premises. */
    private Long subscriberId;

    /** Which router or OLT site serves this node. */
    private Long routerId;

    private String address;

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

    /** Free ports, or null when the kind does not track capacity. */
    @Transient
    public Integer getFree() {
        if (capacity == null) {
            return null;
        }
        return Math.max(0, capacity - (used == null ? 0 : used));
    }
}
