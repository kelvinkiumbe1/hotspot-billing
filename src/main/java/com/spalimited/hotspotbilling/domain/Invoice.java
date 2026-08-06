package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A monthly invoice for a PPPoE subscriber. Issued by InvoiceJob a few
 * days before the subscription lapses and marked PAID when the money
 * arrives, so the admin has a proper receivables list.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    public enum Status { UNPAID, PAID, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-facing number, e.g. "INV-2026-000042". */
    @Column(nullable = false, unique = true)
    private String number;

    @ManyToOne(optional = false)
    @JoinColumn(name = "subscriber_id")
    private Subscriber subscriber;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Column(nullable = false)
    private int months = 1;

    @Column(nullable = false)
    private LocalDate issuedOn;

    @Column(nullable = false)
    private LocalDate dueOn;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UNPAID;

    private Instant paidAt;

    /** Receipt/M-Pesa reference recorded when it was settled. */
    private String paymentReference;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
