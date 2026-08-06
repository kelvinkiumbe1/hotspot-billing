package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A business cost, so reports can show profit rather than just revenue. */
@Entity
@Table(name = "expenses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

    /** Typical WISP cost buckets. */
    public enum Category { BANDWIDTH, EQUIPMENT, RENT, SALARIES, TRANSPORT, POWER, LICENCES, MARKETING, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Business date the cost belongs to (not the entry time). */
    @Column(nullable = false)
    private LocalDate incurredOn;

    private String recordedBy;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
