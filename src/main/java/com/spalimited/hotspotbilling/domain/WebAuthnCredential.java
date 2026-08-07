package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One passkey belonging to a staff member. A person can have several — a
 * work PC and a phone — so a lost device is not a lockout on its own.
 *
 * <p>The attestation object is stored whole because it carries the public
 * key and AAGUID needed to verify every future assertion; the signature
 * counter is tracked separately since it moves on each sign-in.
 */
@Entity
@Table(name = "webauthn_credentials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAuthnCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long staffUserId;

    /** base64url credential id the browser echoes on every assertion. */
    @Column(nullable = false, unique = true)
    private String credentialId;

    @Column(nullable = false)
    private byte[] attestationObject;

    @Builder.Default
    @Column(nullable = false)
    private long signCount = 0;

    private String label;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
