package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One pass handed out on credit, and what became of the debt.
 *
 * <p>The debt is not chased separately: it rides on the customer's next
 * purchase, added to that purchase's M-Pesa amount. So a customer either comes
 * back — and settles as a side effect of buying again — or they don't, and the
 * exposure is capped at one small pass.
 */
@Entity
@Table(name = "credit_advances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditAdvance {

    public enum Status {
        /** Taken, not yet settled. */
        OUTSTANDING,
        REPAID,
        /** Past due and given up on; the customer takes no more credit. */
        DEFAULTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String phoneNumber;

    private Long planId;

    @Column(length = 64)
    private String voucherCode;

    /** What the pass cost. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDue;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OUTSTANDING;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant dueAt;

    private Instant repaidAt;

    @Column(length = 300)
    private String repaidNote;

    /** When the "please settle" message went out, so it fires once. */
    private Instant remindedAt;
}
