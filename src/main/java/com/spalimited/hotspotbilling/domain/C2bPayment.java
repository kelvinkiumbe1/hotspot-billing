package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A PayBill (C2B) payment pushed to us by Safaricom. Every confirmation is
 * stored — matched ones extend a subscription, unmatched ones sit in the
 * admin's "Unmatched payments" list to be applied by hand.
 */
@Entity
@Table(name = "c2b_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class C2bPayment {

    public enum Status { MATCHED, UNMATCHED, APPLIED_MANUALLY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Safaricom receipt, e.g. "SHK61H2QRT" — unique, so replays are ignored. */
    @Column(nullable = false, unique = true)
    private String transactionId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    private String phoneNumber;

    /** The account number the customer typed (their PPPoE username). */
    private String billRefNumber;

    private String payerName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UNMATCHED;

    /** Subscriber this payment was credited to, when matched. */
    private Long subscriberId;

    private Integer monthsCredited;

    @Column(length = 300)
    private String note;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
