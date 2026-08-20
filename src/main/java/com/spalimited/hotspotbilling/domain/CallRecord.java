package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** One call, inbound or outbound, and what became of it. */
@Entity
@Table(name = "call_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallRecord {

    public enum Direction { INBOUND, OUTBOUND }

    /**
     * RINGING until somebody picks up.
     *
     * <p>MISSED and FAILED are kept apart on purpose. A missed call is a
     * customer who rang and got nobody, which is a callback somebody owes; a
     * failed call never reached a phone at all and is nearly always a
     * configuration problem. Collapsing them would bury the second in a list
     * people learn to skim.
     */
    public enum Status { RINGING, ANSWERED, COMPLETED, MISSED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The provider session id: the key every callback arrives quoting. */
    @Column(name = "session_id", nullable = false, unique = true, length = 120)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Direction direction;

    @Column(name = "caller_number", length = 32)
    private String callerNumber;

    @Column(name = "destination_number", length = 32)
    private String destinationNumber;

    @Column(name = "subscriber_id")
    private Long subscriberId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    /** The provider's own words, kept verbatim rather than mapped. */
    @Column(name = "hangup_cause", length = 120)
    private String hangupCause;

    @Column(name = "recording_url", length = 500)
    private String recordingUrl;

    @Column(precision = 10, scale = 4)
    private BigDecimal cost;

    @Column(length = 8)
    private String currency;

    @Column(length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
