package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One commission payment to one agent, and how far it got.
 *
 * <p>The lifecycle exists because M-Pesa B2C is asynchronous: Daraja accepting
 * the request says only that it will try. Until the result arrives the money
 * has not moved, so the agent's paid total must not move either — otherwise a
 * transfer that failed would leave them silently short, which is the worst
 * possible bug in a commission system.
 */
@Entity
@Table(name = "commission_payouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommissionPayout {

    public enum Status {
        /** Worked out and queued, waiting to be released. */
        PENDING,
        /** Daraja accepted it; the result decides whether it actually paid. */
        SENT,
        PAID,
        FAILED,
        /** The operator moved the money themselves and is recording it here. */
        MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long agentId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    /** Correlates the async B2C result that decides PAID or FAILED. */
    @Column(length = 120)
    private String conversationId;

    @Column(length = 64)
    private String receipt;

    @Column(length = 500)
    private String error;

    @Column(length = 120)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    private Instant completedAt;

    /** True while the money is committed but not yet settled either way. */
    @jakarta.persistence.Transient
    public boolean isInFlight() {
        return status == Status.PENDING || status == Status.SENT;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
