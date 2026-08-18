package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An M-Pesa STK-push payment attempt and its outcome.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    public enum Status { PENDING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    /** Daraja CheckoutRequestID used to match the async callback. */
    @Column(unique = true)
    private String checkoutRequestId;

    private String mpesaReceiptNumber;

    /**
     * Which rail took this payment. Not derivable afterwards once an operator
     * has more than one configured, and reconciliation has to be able to ask.
     */
    @Column(length = 24)
    private String provider;

    /**
     * Where to send the customer to pay, for rails that use hosted checkout.
     * Null for M-Pesa, which prompts the handset instead — so a caller has to
     * cope with both, which is the honest shape of the problem.
     *
     * <p>Transient on purpose: it is part of the answer to "I just started a
     * payment", not a fact about the payment worth storing. The URL is
     * single-use and stale within minutes.
     */
    @jakarta.persistence.Transient
    private String checkoutUrl;

    /** For pay-per-minute custom passes: the minutes the customer asked for. */
    private Integer customMinutes;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant completedAt;

    @OneToOne
    @JoinColumn(name = "voucher_id")
    private Voucher voucher;
}
