package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Something wrong with the system itself, as opposed to the network it runs.
 *
 * <p>Keyed by the check that raised it, so a fault lasting three days is one
 * ageing row rather than three days of identical texts, and clears itself the
 * moment the condition goes away.
 */
@Entity
@Table(name = "health_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthAlert {

    public enum Severity { CRITICAL, WARNING }

    public enum Status { OPEN, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String checkKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 500)
    private String detail;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    private Instant resolvedAt;

    /** When the operator was texted, so a persistent fault doesn't nag. */
    private Instant notifiedAt;
}
