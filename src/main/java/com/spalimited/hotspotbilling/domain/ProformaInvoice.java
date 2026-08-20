package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A priced quote with a number on it, issued before any money moves.
 *
 * <p>A company will not pay an ISP without one: their finance department needs a
 * document to raise a payment against. See V74 for why this is its own table
 * rather than a kind on {@link Invoice}.
 */
@Entity
@Table(name = "proforma_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProformaInvoice {

    /**
     * ISSUED until something happens to it. EXPIRED is set lazily on read rather
     * than by a job -- a quote is expired the moment its date passes whether or
     * not anything ran.
     */
    public enum Status { ISSUED, CONVERTED, EXPIRED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String number;

    @Column(name = "subscriber_id", nullable = false)
    private Long subscriberId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "net_amount", precision = 12, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", precision = 12, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "vat_inclusive")
    private Boolean vatInclusive;

    @Builder.Default
    @Column(nullable = false)
    private int months = 1;

    @Column(length = 500)
    private String description;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Whether this quote can still be turned into an invoice.
     *
     * <p>Computed rather than stored, so a quote is out of date the day it says
     * it is and not the day a job next runs.
     */
    @Transient
    public boolean isLive() {
        return status == Status.ISSUED && !validUntil.isBefore(LocalDate.now());
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
