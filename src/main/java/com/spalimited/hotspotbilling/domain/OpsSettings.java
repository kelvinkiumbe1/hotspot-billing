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
 * How closely the system watches itself. Single row (id = 1).
 */
@Entity
@Table(name = "ops_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpsSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    // --- Backups ---

    /** Alert when a backup hasn't been reported, or reported a failure. */
    @Builder.Default
    @Column(nullable = false)
    private boolean backupWatchEnabled = true;

    /**
     * How long may pass without a good backup before that is an alert. Slightly
     * over a day by default, so a nightly job that runs a little late is not
     * treated as a missed one.
     */
    @Builder.Default
    @Column(nullable = false)
    private int backupExpectedHours = 26;

    /** A dump smaller than this is treated as a failure, however it exited. */
    @Builder.Default
    @Column(nullable = false)
    private long backupMinBytes = 4096;

    // --- Health ---

    @Builder.Default
    @Column(nullable = false)
    private boolean healthWatchEnabled = true;

    /** Hours without any M-Pesa callback before the payment pipe is suspect. */
    @Builder.Default
    @Column(nullable = false)
    private int callbackSilenceHours = 6;

    /** Overnight window where no customer traffic is expected, so no alarm. */
    @Builder.Default
    @Column(nullable = false)
    private int quietFromHour = 22;

    @Builder.Default
    @Column(nullable = false)
    private int quietToHour = 6;

    /**
     * An external watchdog to ping on a schedule (healthchecks.io and similar).
     * The point is the case this system cannot report on: itself being dead.
     * If the pings stop, that service raises the alarm.
     */
    @Column(length = 512)
    private String heartbeatUrl;
}
