package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One outage, however many devices it took down.
 *
 * <p>Routers fail in groups far more often than alone — a power cut, an uplink,
 * a backhaul — and treating each device as its own event produces a burst of
 * identical alerts about a single cause, then a burst of identical apologies.
 * Grouping them means one message to the customers who were actually affected,
 * one ticket for whoever is fixing it, and one honest record of how long the
 * network was down.
 */
@Entity
@Table(name = "incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Incident {

    public enum Status { OPEN, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Column(nullable = false, length = 200)
    private String title;

    /** Routers currently or previously down in this incident. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "incident_routers", joinColumns = @JoinColumn(name = "incident_id"))
    @Column(name = "router_id")
    private Set<Long> routerIds = new LinkedHashSet<>();

    /** When customers were told, so a long outage doesn't message them hourly. */
    private Instant notifiedAt;

    @Builder.Default
    @Column(nullable = false)
    private int notifiedCount = 0;

    private Instant resolvedNotifiedAt;

    @Builder.Default
    @Column(nullable = false)
    private long compensatedMinutes = 0;

    @Builder.Default
    @Column(nullable = false)
    private int compensatedCount = 0;

    /** The support ticket opened for whoever is fixing it. */
    private Long ticketId;

    @Transient
    public Duration getDuration() {
        return Duration.between(startedAt, endedAt == null ? Instant.now() : endedAt);
    }
}
