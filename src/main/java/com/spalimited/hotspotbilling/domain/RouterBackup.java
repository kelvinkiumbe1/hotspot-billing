package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One version of one router's configuration. See V72__router_config_backup.sql
 * for why this is a row per version rather than a row per night.
 */
@Entity
@Table(name = "router_backups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouterBackup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "router_id", nullable = false)
    private Long routerId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(nullable = false, length = 24)
    private String method;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "byte_count", nullable = false)
    private int byteCount;

    /**
     * The configuration itself. Lazy because the list screen shows a dozen of
     * these and none of them needs the text.
     */
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false, columnDefinition = "text")
    private String content;
}
