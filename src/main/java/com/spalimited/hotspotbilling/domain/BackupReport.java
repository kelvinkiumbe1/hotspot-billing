package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One night's backup, as reported by the script that took it.
 *
 * <p>The dump itself lives on disk somewhere this application cannot see, so
 * what is recorded here is the script's own account of the run: how big the
 * dump was, whether it was restored into a scratch database to prove it reads,
 * and whether a copy left the machine. That is enough to notice the two
 * failures that matter — backups that stopped happening, and backups that are
 * happening but are useless.
 */
@Entity
@Table(name = "backup_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which tenant's database this was; "default" for a single-tenant install. */
    @Column(nullable = false, length = 64)
    private String tenant;

    @Column(nullable = false)
    private boolean ok;

    @Builder.Default
    @Column(nullable = false)
    private long bytes = 0;

    /** The dump was restored into a scratch database and read back. */
    @Builder.Default
    @Column(nullable = false)
    private boolean verified = false;

    /** A copy reached somewhere other than this machine's own disk. */
    @Builder.Default
    @Column(nullable = false)
    private boolean offsite = false;

    @Builder.Default
    @Column(nullable = false)
    private long durationMs = 0;

    @Column(length = 500)
    private String error;

    @Column(nullable = false)
    private Instant reportedAt;
}
