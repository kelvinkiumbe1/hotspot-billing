package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** A monthly-subscription payment (M-Pesa STK or cash recorded by the admin). */
@Entity
@Table(name = "subscription_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionPayment {

    /**
      * MPESA is kept for the rows already written by the Daraja-only path;
      * ONLINE is any rail reached through the provider abstraction, with
      * {@code provider} saying which.
      */
    public enum Method { MPESA, CASH, ONLINE }

    public enum Status { PENDING, SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "subscriber_id")
    private Subscriber subscriber;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private int months;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Method method;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(unique = true)
    /**
      * What the rail will quote back. Named for Daraja because that is all it
      * ever held; it now holds Paystack's reference, Wave's session id and the
      * rest, and the matching in SubscriptionService looks here for all of them.
      */
    private String checkoutRequestId;

    /** Which rail took it. Null on the rows written before more than one existed. */
    private String provider;

    private String mpesaReceiptNumber;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
