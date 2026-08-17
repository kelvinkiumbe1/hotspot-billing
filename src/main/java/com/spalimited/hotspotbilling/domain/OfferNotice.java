package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;

/**
 * That we told this person about an offer. Kept so the same customer is not
 * messaged about the night rate every night — the fastest way to turn a good
 * offer into a blocked number.
 */
@Entity
@Table(name = "offer_notices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfferNotice {

    public static final String KIND_OFFPEAK = "OFFPEAK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false)
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (sentAt == null) {
            sentAt = Instant.now();
        }
    }
}
