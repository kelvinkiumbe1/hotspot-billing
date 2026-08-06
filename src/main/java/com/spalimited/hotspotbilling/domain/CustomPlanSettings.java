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

import java.math.BigDecimal;

/**
 * Settings for the pay-per-minute "custom time" pass on the customer
 * portal: the customer types how many minutes they need and the price is
 * computed from the hourly rate. Single row (id = 1), managed from the
 * admin Settings page.
 */
@Entity
@Table(name = "custom_plan_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomPlanSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    /** Rate the price is computed from: KES per hour, pro-rated per minute. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerHour;

    /** MikroTik rate limit for custom passes, e.g. "5M/5M". */
    private String bandwidth;

    @Column(nullable = false)
    private int minMinutes;

    @Column(nullable = false)
    private int maxMinutes;
}
