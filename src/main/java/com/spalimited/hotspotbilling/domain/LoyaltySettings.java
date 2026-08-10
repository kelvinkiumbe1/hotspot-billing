package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The loyalty programme: customers earn points as they spend and redeem
 * them for free minutes. Single row (id = 1). Rates are whole numbers so
 * the maths stays predictable for both the operator and the customer.
 */
@Entity
@Table(name = "loyalty_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltySettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** Points earned for every KES 100 spent. */
    @Builder.Default
    @Column(nullable = false)
    private int pointsPerHundredKes = 10;

    /** How many points buy one minute of free access when redeeming. */
    @Builder.Default
    @Column(nullable = false)
    private int pointsPerMinute = 5;

    /** Smallest redemption allowed, in minutes. */
    @Builder.Default
    @Column(nullable = false)
    private int minRedeemMinutes = 30;

    /** Largest redemption allowed in one go, in minutes. */
    @Builder.Default
    @Column(nullable = false)
    private int maxRedeemMinutes = 1440;
}
