package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A customer support request, e.g. "my connection keeps dropping".
 * Customers open tickets through the public API; admins reply and
 * resolve them from the dashboard.
 */
@Entity
@Table(name = "support_tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportTicket {

    public enum Priority { LOW, MEDIUM, HIGH }

    public enum Status { OPEN, IN_PROGRESS, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String subject;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.MEDIUM;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Technicians the ticket is assigned to. A job can need more than one
     * pair of hands, so this is a set rather than a single owner.
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ticket_assignees", joinColumns = @JoinColumn(name = "ticket_id"))
    @Column(name = "technician_id")
    private Set<Long> assigneeIds = new LinkedHashSet<>();

    /** Set when a staff member raises the ticket rather than a customer. */
    private String createdBy;

    // --- Work tracking, so the office can see a job is genuinely under way ---

    /**
     * When someone was first put on this job. Set on the first assignment
     * and never moved, so reassigning does not restart the clock and make
     * a long-running job look fresh.
     */
    private Instant workStartedAt;

    /** When it was closed, and by whom. */
    private Instant resolvedAt;

    private String resolvedBy;

    /**
     * When the assigned technician was last chased about this job. Stamped so a
     * job that stays stale is nudged once rather than on every sweep.
     */
    private Instant lastNudgedAt;

    /** When the operator was told nobody had picked this job up. Same reasoning. */
    private Instant queueAlertedAt;

    /**
     * How long the job took, or how long it has been running so far. Live
     * rather than stored, so a ticket left open keeps counting instead of
     * showing a figure frozen at the last save.
     */
    @Transient
    public Long getWorkingMinutes() {
        if (workStartedAt == null) {
            return null;
        }
        Instant end = resolvedAt != null ? resolvedAt : Instant.now();
        return java.time.Duration.between(workStartedAt, end).toMinutes();
    }

    @Builder.Default
    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<TicketMessage> messages = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
