package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One outbound message, kept so the outbox can show what actually went out
 * and a failure can be retried. Written for every send, including the
 * automatic expiry reminders, so the log is the whole picture rather than
 * only the campaigns someone typed by hand.
 */
@Entity
@Table(name = "outbound_messages", indexes = {
        @Index(name = "idx_outbound_created", columnList = "createdAt"),
        @Index(name = "idx_outbound_status", columnList = "status"),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundMessage {

    public enum Channel { WHATSAPP, SMS }

    /**
     * SENT means the gateway accepted it. Real delivery receipts need a
     * webhook from the provider, so we do not claim DELIVERED on our own.
     */
    public enum Status { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(nullable = false)
    private String recipient;

    /** Who it went to, when we know — for the outbox listing. */
    private String recipientName;

    @Column(nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Why the gateway rejected it, when it did. */
    @Column(length = 500)
    private String error;

    /** What the send cost, when the gateway tells us. */
    private BigDecimal cost;

    /** Groups the messages produced by one campaign send. */
    private String campaignRef;

    /** Blank for automatic messages; a username for hand-typed ones. */
    private String sentBy;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
