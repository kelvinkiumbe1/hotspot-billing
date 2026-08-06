package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A physical item the business owns: routers, ONTs, access points, cable
 * drums and the like. An item is either sitting in a store, out with a
 * technician, installed at a subscriber's place, faulty or retired.
 */
@Entity
@Table(name = "equipment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Equipment {

    public enum Kind { ROUTER, ONT, ACCESS_POINT, SWITCH, CABLE, ANTENNA, POWER, TOOL, OTHER }

    public enum Status { IN_STOCK, WITH_TECHNICIAN, DEPLOYED, FAULTY, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What it is, e.g. "TP-Link EC220 router". */
    @Column(nullable = false)
    private String name;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.OTHER;

    private String model;

    /** Unique when present, so the same box cannot be logged twice. */
    @Column(unique = true)
    private String serialNumber;

    private String macAddress;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_STOCK;

    /**
     * Consumables like cable are counted rather than serialised, so one row
     * can stand for many units.
     */
    @Builder.Default
    private Integer quantity = 1;

    private BigDecimal purchaseCost;

    private LocalDate purchasedAt;

    /** Months of supplier warranty from the purchase date, when known. */
    private Integer warrantyMonths;

    /** Set while the item is out with a technician. */
    private Long technicianId;

    /** Set once the item is installed at a subscriber's premises. */
    private Long subscriberId;

    /** Which store or branch holds it. */
    private Long branchId;

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

    /** Consumables default to one unit so totals never read as zero. */
    public int getQuantityOrOne() {
        return quantity == null || quantity < 1 ? 1 : quantity;
    }

    /** Warranty end date, or null when the purchase or term is unknown. */
    public LocalDate getWarrantyExpiry() {
        if (purchasedAt == null || warrantyMonths == null || warrantyMonths <= 0) {
            return null;
        }
        return purchasedAt.plusMonths(warrantyMonths);
    }
}
