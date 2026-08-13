package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A new customer's claim that they were referred with someone's code. Created
 * PENDING when they enter the code (before their first purchase) and SETTLED
 * when that first purchase rewards both parties. One per referee phone.
 */
@Entity
@Table(name = "referral_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralClaim {

    public enum Status { PENDING, SETTLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String refereePhone;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String referrerPhone;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant settledAt;
}
