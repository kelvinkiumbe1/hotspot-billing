package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A monthly PPPoE subscriber (home/office customer). Their router dials
 * in with the PPPoE username/password; access lasts until paidUntil and
 * the SubscriptionJob suspends the account when payment lapses.
 */
@Entity
@Table(name = "subscribers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscriber {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false, unique = true)
    private String pppoeUsername;

    @JsonIgnore
    @Column(nullable = false)
    private String pppoePassword;

    /** MikroTik rate limit, e.g. "10M/10M". */
    private String bandwidth;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyFee;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private Instant paidUntil;

    /** The paidUntil we last sent an expiry-reminder SMS about. */
    private Instant remindedForExpiry;

    /** The paidUntil we last fired an automatic renewal STK prompt for. */
    private Instant autoStkForExpiry;

    /** How the most recent successful payment was made (MPESA/CASH). */
    private String lastPaymentMethod;

    private Instant lastPaymentAt;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
