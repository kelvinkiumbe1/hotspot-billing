package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One outstanding "is this number yours" challenge.
 *
 * <p>The code is stored hashed. It is short-lived and low-value, but it is still a
 * credential delivered by SMS, and a database dump containing live codes for
 * numbers inside their window is a real if brief hole. Hashing costs nothing.
 */
@Entity
@Table(name = "phone_verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, length = 32)
    private String phoneNumber;

    @Column(name = "code_hash", nullable = false, length = 128)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Counted, so a six-digit code cannot simply be walked. */
    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * What the code was issued for, so one sent to confirm a number cannot be
     * spent authorising something else.
     */
    @Builder.Default
    @Column(nullable = false, length = 32)
    private String purpose = "GENERIC";

    @Column(name = "requested_ip", length = 64)
    private String requestedIp;
}
