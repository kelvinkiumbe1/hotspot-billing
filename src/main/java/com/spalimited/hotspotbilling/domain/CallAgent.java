package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Somebody who answers the support line.
 *
 * <p>Its own table rather than a flag on a staff login, because the people who
 * answer calls are not the same set as the people with admin accounts: a
 * technician taking calls on a personal handset has no login and should not need
 * one to be on the rota.
 */
@Entity
@Table(name = "call_agents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    /** Lower rings first. Not unique: two agents at one priority is normal. */
    @Builder.Default
    @Column(nullable = false)
    private int priority = 10;

    /** On the rota at all, as distinct from free right now. */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "busy_until")
    private Instant busyUntil;

    @Column(name = "staff_id")
    private Long staffId;

    /**
     * The field technician this agent row stands for, if any.
     *
     * <p>Office agents are created by hand and have none. A technician gets one
     * the first time they place a call, so the rota does not fill with duplicates
     * of the same person.
     */
    @Column(name = "technician_id")
    private Long technicianId;

    /**
     * Whether calls coming IN ring this agent.
     *
     * <p>False for technicians. Someone ringing the business should not reach
     * whoever happens to be up a ladder — placing a call and taking one are
     * different jobs, and conflating them is how a customer gets a support line
     * that nobody can answer properly.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean inbound = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Free to be rung.
     *
     * <p>busyUntil is a lease with an expiry rather than a flag, because the
     * event that clears it is a callback from the voice provider -- and a
     * callback that never arrives would otherwise leave an agent permanently
     * unreachable with nothing on screen to explain why.
     */
    @Transient
    public boolean isAvailable() {
        return active && (busyUntil == null || busyUntil.isBefore(Instant.now()));
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
