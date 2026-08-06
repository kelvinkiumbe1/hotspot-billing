package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A movement on a customer's account that is not an invoice or a payment:
 * a credit note, a goodwill discount, a write-off or a penalty. These are
 * the only ledger rows that are stored — everything else on the statement
 * is derived from invoices and payments, so the balance cannot drift from
 * the records that explain it.
 */
@Entity
@Table(name = "ledger_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerAdjustment {

    /**
     * CREDIT_NOTE and DISCOUNT reduce what the customer owes. WRITE_OFF
     * clears a debt we have given up on. PENALTY increases it.
     */
    public enum Kind { CREDIT_NOTE, DISCOUNT, WRITE_OFF, PENALTY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Subscriber subscriber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    /** Always positive; the kind decides which way it moves the balance. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private LocalDate appliedOn;

    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (appliedOn == null) {
            appliedOn = LocalDate.now();
        }
    }

    /**
     * Signed effect on the balance, where positive means the customer owes
     * more. A penalty adds; everything else reduces.
     */
    @Transient
    public BigDecimal getSignedAmount() {
        return kind == Kind.PENALTY ? amount : amount.negate();
    }
}
