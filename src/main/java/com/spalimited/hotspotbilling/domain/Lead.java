package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A prospective customer who has shown interest but is not signed up yet.
 * Converting a lead creates a PPPoE subscriber and links the two.
 */
@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {

    public enum Source { WALK_IN, REFERRAL, ONLINE, PHONE_CALL, FIELD_VISIT, OTHER }

    public enum Status { NEW, CONTACTED, QUOTED, CONVERTED, LOST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phoneNumber;

    private String location;

    /** What the prospect is asking for, e.g. "10 Mbps home". */
    private String interestedIn;

    /** Quoted monthly fee, when one has been given. */
    private BigDecimal quotedFee;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.WALK_IN;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NEW;

    @Column(length = 1000)
    private String notes;

    private String createdBy;

    /** Set once the lead becomes a paying subscriber. */
    private Long subscriberId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

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
