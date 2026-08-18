package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A customer's standing permission to be charged.
 *
 * <p>The point of it is what stops happening. Dunning, win-back, expiry nudges
 * and auto-STK all exist to recover a renewal the customer forgot; a live
 * mandate means the money arrives without anyone being asked, so none of that
 * machinery needs to run for this subscriber.
 *
 * <p>PENDING is not ACTIVE, and the distinction matters more than it looks.
 * Ratiba needs the customer to approve on their handset, which is neither
 * instant nor guaranteed — treating a pending mandate as a reason to stop
 * chasing means a customer who never approved is never chased either, and
 * quietly lapses.
 */
@Entity
@Table(name = "payment_mandates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentMandate {

    public enum Status { PENDING, ACTIVE, CANCELLED, FAILED }

    /**
     * Who initiates the money, which is the whole difference between the two
     * kinds of standing order.
     *
     * <p>PUSH is M-Pesa Ratiba: the customer approves on their handset and
     * Safaricom sends the money on schedule. Nothing here acts; it records the
     * money arriving. PULL is a stored authorisation — Paystack's authorization
     * code, Flutterwave's card token, Stripe's payment method. Nothing arrives
     * unless this system charges it.
     *
     * <p>Treating them alike breaks in both directions: a PUSH mandate charged
     * by us takes the money twice, and a PULL mandate merely waited on never
     * collects at all while the customer is no longer being chased.
     */
    public enum Model { PUSH, PULL }

    public enum Frequency { WEEKLY, MONTHLY, QUARTERLY, YEARLY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long subscriberId;

    @Column(nullable = false, length = 24)
    private String provider;

    /** The provider's handle for it, where they give one back. */
    @Column(length = 120)
    private String externalRef;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Model model = Model.PUSH;

    /**
     * The reusable authorisation.
     *
     * <p>Never returned by any API this system exposes — it is the thing that
     * can move a customer's money.
     */
    @Column(length = 255)
    private String token;

    /** When the customer agreed, and the payment that proved it. */
    private Instant consentedAt;

    @Column(length = 120)
    private String consentReference;

    private Instant lastAttemptAt;

    /**
     * How many collections in a row have failed.
     *
     * <p>A card expires, a wallet empties. After a few of these the mandate is
     * not a reason to stop chasing any more, and saying so is the difference
     * between a customer being asked to pay and a customer quietly lapsing.
     */
    @Builder.Default
    @Column(nullable = false)
    private int consecutiveFailures = 0;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Frequency frequency = Frequency.MONTHLY;

    @Column(nullable = false)
    private LocalDate startsOn;

    private LocalDate endsOn;

    /**
     * When money last actually arrived under this mandate.
     *
     * <p>A mandate that has been ACTIVE for three months and never collected is
     * broken, and without this it looks exactly like one that is working.
     */
    private Instant lastCollectedAt;

    @Builder.Default
    @Column(nullable = false)
    private int collections = 0;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 120)
    private String createdBy;

    private Instant cancelledAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Whether this is a reason to stop chasing the customer.
     *
     * <p>True for both models: a PUSH mandate collects on its own and a PULL
     * mandate is collected by MandateService. Either way nobody should be
     * texted about a renewal that is already arranged.
     */
    @Transient
    public boolean isCollecting() {
        return status == Status.ACTIVE;
    }

    /**
     * Whether this system has to go and take the money.
     *
     * <p>The question the renewal job asks. A PUSH mandate answers no and must
     * not be charged — Safaricom is already sending it, and charging as well
     * takes the money twice.
     */
    @Transient
    public boolean weCollect() {
        return status == Status.ACTIVE && model == Model.PULL && token != null;
    }

    /**
     * True when it claims to be live but has never taken any money.
     *
     * <p>Worth surfacing: the operator has stopped chasing this customer on the
     * strength of a mandate that is not working, and the first sign otherwise
     * would be the customer lapsing.
     */
    @Transient
    public boolean isSuspect() {
        if (status != Status.ACTIVE) {
            return false;
        }
        // A PULL mandate with no token is the dangerous one: it says ACTIVE,
        // the operator has stopped chasing, and there is nothing to charge.
        if (model == Model.PULL && token == null) {
            return true;
        }
        boolean overdue = startsOn != null
                && startsOn.plusDays(35).isBefore(LocalDate.now());
        return overdue && collections == 0;
    }
}
