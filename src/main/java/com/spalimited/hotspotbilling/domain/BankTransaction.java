package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One credit line from a bank statement, and what became of it. */
@Entity
@Table(name = "bank_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankTransaction {

    /**
     * Where this line stands.
     *
     * <p>MATCHED is deliberately not APPLIED. A confident guess still has to be
     * confirmed by somebody who can read a name and recognise it, because
     * crediting the wrong customer costs two people an afternoon and leaves the
     * right one still cut off.
     */
    public enum Status { UNMATCHED, MATCHED, APPLIED, IGNORED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "import_id", nullable = false)
    private Long importId;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(nullable = false, length = 1000)
    private String narration;

    @Column(name = "bank_reference", length = 120)
    private String bankReference;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "dedupe_key", nullable = false, length = 64)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "match_reason")
    private String matchReason;

    @Column(name = "subscriber_id")
    private Long subscriberId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by", length = 120)
    private String decidedBy;
}
