package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The reverse of an invoice, as a document.
 *
 * <p>{@link LedgerAdjustment} already had a CREDIT_NOTE kind, which moves the
 * balance and carries a reason. That is a number in a ledger: it has no
 * reference, does not say which invoice it reverses, and does not reverse the
 * VAT, which is the part a tax authority cares about. This is the document, and
 * it creates that ledger row rather than replacing it.
 *
 * <p>Amounts are always positive. The sign lives in what the document is, not in
 * the number, so a credit of -2500 cannot be typed in by accident and read back
 * later as a charge.
 */
@Entity
@Table(name = "credit_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String number;

    @Column(name = "subscriber_id", nullable = false)
    private Long subscriberId;

    /** Nullable: a goodwill credit does not always answer to one invoice. */
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "net_amount", precision = 12, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", precision = 12, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "adjustment_id")
    private Long adjustmentId;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
