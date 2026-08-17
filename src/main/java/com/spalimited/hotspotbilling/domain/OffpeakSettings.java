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
 * Selling the hours the network is idle. Single row (id = 1).
 *
 * <p>Off by default: this changes what customers are charged, which is the
 * operator's decision and nobody else's.
 */
@Entity
@Table(name = "offpeak_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OffpeakSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /**
     * Work the quiet window out from recorded traffic rather than using the
     * hours below. The operator can still override: they know the estate, and
     * the data only knows last month.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean autoWindow = true;

    @Builder.Default
    @Column(nullable = false)
    private int lookbackDays = 14;

    @Builder.Default
    @Column(nullable = false)
    private int windowStartHour = 22;

    /** Exclusive, and may be smaller than the start — a window can cross midnight. */
    @Builder.Default
    @Column(nullable = false)
    private int windowEndHour = 6;

    @Builder.Default
    @Column(nullable = false)
    private int discountPercent = 30;

    // --- Telling people ---

    @Builder.Default
    @Column(nullable = false)
    private boolean notify = false;

    @Builder.Default
    @Column(nullable = false, length = 40)
    private String audience = "expired_hotspot_users";

    @Builder.Default
    @Column(nullable = false)
    private int maxMessagesPerRun = 100;

    /** Nobody hears about the night offer more often than this. */
    @Builder.Default
    @Column(nullable = false)
    private int minDaysBetweenMessages = 7;

    private LocalDate lastNotifiedOn;
}
