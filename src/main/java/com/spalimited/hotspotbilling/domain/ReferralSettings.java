package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Referral programme settings (singleton). Rewards are paid as free-minute
 * vouchers so they work whether or not the loyalty-points programme is on.
 */
@Entity
@Table(name = "referral_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** Free minutes granted to the existing customer who referred. */
    @Builder.Default
    @Column(nullable = false)
    private int referrerMinutes = 60;

    /** Free minutes granted to the new customer who was referred. */
    @Builder.Default
    @Column(nullable = false)
    private int refereeMinutes = 30;
}
