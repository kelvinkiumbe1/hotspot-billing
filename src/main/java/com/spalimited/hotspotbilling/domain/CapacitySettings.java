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

import java.time.LocalDate;

/**
 * Watching each site's busy hour against what its link can carry.
 * Single row (id = 1).
 */
@Entity
@Table(name = "capacity_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapacitySettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** Weeks of history to read the trend from. */
    @Builder.Default
    @Column(nullable = false)
    private int lookbackDays = 28;

    /** Busy-hour throughput above this share of capacity is "getting full". */
    @Builder.Default
    @Column(nullable = false)
    private int warnPercent = 70;

    @Builder.Default
    @Column(nullable = false)
    private int criticalPercent = 90;

    /** Below this, the site is capacity bought and not sold. */
    @Builder.Default
    @Column(nullable = false)
    private int underusedPercent = 20;

    @Builder.Default
    @Column(nullable = false)
    private boolean notify = false;

    /** 1 = Monday … 7 = Sunday. */
    @Builder.Default
    @Column(nullable = false)
    private int notifyDayOfWeek = 1;

    @Builder.Default
    @Column(nullable = false)
    private int notifyHour = 8;

    private LocalDate lastNotifiedOn;
}
