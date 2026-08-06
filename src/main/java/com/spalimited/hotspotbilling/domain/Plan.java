package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * An internet access package a customer can buy, e.g. "1 Hour @ 20 KES".
 */
@Entity
@Table(name = "plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** How long access lasts once activated, in minutes. */
    @Column(nullable = false)
    private int durationMinutes;

    /** Optional data cap in MB; null means unlimited within the duration. */
    private Integer dataLimitMb;

    /** MikroTik rate limit string, e.g. "5M/5M" (upload/download). */
    private String bandwidth;

    /** Name of the matching MikroTik hotspot user profile. */
    private String mikrotikProfile;

    /**
     * How many devices may use one voucher at the same time (MikroTik
     * shared-users). Null or 0 means 1 device.
     */
    private Integer maxDevices;

    @Transient
    public int getEffectiveMaxDevices() {
        return maxDevices != null && maxDevices > 0 ? maxDevices : 1;
    }

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
