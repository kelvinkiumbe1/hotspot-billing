package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** An audit-log entry: who did what, to which record, and when. */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Username, or "system" for scheduled jobs and callbacks. */
    @Column(nullable = false)
    private String actor;

    /** Verb, e.g. "voucher.generate", "subscriber.suspend". */
    @Column(nullable = false)
    private String action;

    /** Human-readable summary shown in the log. */
    @Column(nullable = false, length = 500)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
