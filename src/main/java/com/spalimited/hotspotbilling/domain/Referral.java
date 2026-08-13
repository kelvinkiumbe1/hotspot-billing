package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A customer's own shareable referral code, keyed by phone (the identity the
 * portal has). {@code successfulReferrals} counts friends who joined and made
 * a first purchase with this code.
 */
@Entity
@Table(name = "referrals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral {

    @Id
    private String phoneNumber;

    @Column(nullable = false, unique = true)
    private String code;

    @Builder.Default
    @Column(nullable = false)
    private int successfulReferrals = 0;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
