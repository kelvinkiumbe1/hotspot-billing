package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A signed-in session.
 *
 * <p>A rotating six-digit code cannot be replayed on every request the way
 * a password can, so proving it once has to hand back something that can.
 * Tokens are opaque random strings held here rather than self-contained
 * JWTs, because that makes revocation immediate — signing someone out, or
 * cutting off a stolen session, is a delete rather than a wait for expiry.
 */
@Entity
@Table(name = "auth_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 88)
    private String token;

    @Column(nullable = false)
    private Long staffUserId;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant lastUsedAt;

    /** So somebody can recognise their own sessions when revoking one. */
    private String userAgent;

    private String ipAddress;

    @Transient
    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
