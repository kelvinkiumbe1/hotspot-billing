package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Records that a phone number has taken its one free trial. */
@Entity
@Table(name = "trial_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrialClaim {

    @Id
    private String phoneNumber;

    @Column(nullable = false)
    private String voucherCode;

    @Column(nullable = false)
    private Instant claimedAt;

    @PrePersist
    void onCreate() {
        claimedAt = Instant.now();
    }
}
