package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A MikroTik router / site. Multiple routers can be managed from one
 * dashboard; subscribers and vouchers are provisioned on the router they
 * belong to (or the default one).
 */
@Entity
@Table(name = "routers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Router {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Friendly name, e.g. "Westlands Site". */
    @Column(nullable = false, unique = true)
    private String name;

    private String location;

    @Column(nullable = false)
    private String host;

    @Builder.Default
    @Column(nullable = false)
    private int port = 8728;

    private String username;

    @JsonIgnore
    private String password;

    @Builder.Default
    @Column(nullable = false)
    private boolean useSsl = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** Used when a subscriber/voucher has no explicit router. */
    @Builder.Default
    @Column(nullable = false)
    private boolean defaultRouter = false;

    /** Branch/franchise this router belongs to; null means head office. */
    private Long branchId;

    // --- Live monitoring (filled by RouterMonitorJob) ---

    @Builder.Default
    @Column(nullable = false)
    private boolean online = false;

    private Instant lastSeenAt;

    private Instant lastCheckedAt;

    private String lastError;

    /** RouterOS uptime string, e.g. "3w2d10:15:00". */
    private String uptime;

    private String routerOsVersion;

    private String boardName;

    private Integer activeHotspotUsers;

    private Integer activePppoeUsers;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
