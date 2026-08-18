package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * How likely one customer is to leave, and why.
 *
 * <p>The system already chases customers who have left — dunning when a
 * payment fails, win-back once they are gone. Both of those start after the
 * loss. Nobody has ever looked at the customer who is still paying and has
 * quietly stopped using their connection, and that customer is the only one
 * who can still be kept.
 */
@Entity
@Table(name = "retention_scores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetentionScore {

    public enum Band { STEADY, WATCH, AT_RISK, CRITICAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long subscriberId;

    @Builder.Default
    @Column(nullable = false)
    private int score = 0;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Band band = Band.STEADY;

    /**
     * The signals that fired, worst first, in plain words.
     *
     * <p>The whole point of the feature. "At risk" tells an operator nothing
     * they can act on; "hasn't been online in 19 days, and paid 6 days late
     * last month" tells them what to say when they ring.
     */
    @Column(length = 1000)
    private String reasons;

    @Column(length = 255)
    private String suggestedAction;

    /** So a trend can be read — a jump of 30 points is its own signal. */
    private Integer previousScore;

    @Column(nullable = false)
    private Instant scoredAt;

    private Instant acknowledgedAt;

    @Column(length = 120)
    private String acknowledgedBy;

    /** True when the score has climbed sharply since it was last worked out. */
    @Transient
    public boolean isWorsening() {
        return previousScore != null && score - previousScore >= 20;
    }
}
