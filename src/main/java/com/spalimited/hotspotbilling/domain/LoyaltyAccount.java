package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A customer's loyalty balance, keyed by phone number (the only identity
 * the captive portal has). Lifetime totals are kept alongside the current
 * balance so the operator can see engagement, not just what's unspent.
 */
@Entity
@Table(name = "loyalty_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyAccount {

    @Id
    @Column(length = 12)
    private String phoneNumber;

    @Builder.Default
    @Column(nullable = false)
    private long points = 0;

    @Builder.Default
    @Column(nullable = false)
    private long totalEarned = 0;

    @Builder.Default
    @Column(nullable = false)
    private long totalRedeemed = 0;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
