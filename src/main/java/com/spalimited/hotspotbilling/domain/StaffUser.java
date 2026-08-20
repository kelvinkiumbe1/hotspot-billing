package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Set;

/**
 * An office login. Replaces the single shared admin account so the audit
 * log can name who did what, and so an accountant cannot reach the router
 * while support cannot reach the money.
 */
@Entity
@Table(name = "staff_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffUser {

    /**
     * Roles in descending reach. OWNER can do anything including managing
     * staff; MANAGER runs the business day to day but cannot create logins
     * or change gateway credentials; ACCOUNTANT sees money and nothing
     * else writable; SUPPORT handles customers but never finance.
     */
    public enum Role { OWNER, MANAGER, ACCOUNTANT, SUPPORT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    private String phoneNumber;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /**
     * Which branch this login is limited to, or null for head office.
     *
     * <p>Only ever a restriction: a branch id narrows what somebody can reach
     * and never widens it. See BranchScopeFilter for how it is enforced -- the
     * short version is that a branch session may reach an explicit allowlist of
     * endpoints and is refused everywhere else, because a partial filter is how
     * one partner ends up reading another's customer list.
     */
    @Column(name = "branch_id")
    private Long branchId;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Set on the account seeded from application.properties at first boot. */
    @Builder.Default
    @Column(nullable = false)
    private boolean seeded = false;

    /** A read-only evaluation login: every session it holds is blocked from writing. */
    @Builder.Default
    @Column(nullable = false)
    private boolean demo = false;

    private String createdBy;

    private Instant lastLoginAt;

    // --- Two-factor ---

    /** Base32 shared secret. Set when setup starts, trusted once confirmed. */
    private String totpSecret;

    @Builder.Default
    @Column(nullable = false)
    private boolean totpEnabled = false;

    private Instant totpConfirmedAt;

    // --- Passkeys (WebAuthn) ---

    /**
     * Stable per-user handle the authenticator ties its passkey to
     * (base64url of 32 random bytes). Minted lazily on first enrolment and
     * never reused, so it is not the username or the database id.
     */
    private String webauthnUserHandle;

    // --- Lockout ---

    @Builder.Default
    @Column(nullable = false)
    private int failedAttempts = 0;

    /** Set once the limit is passed; only an owner can clear it. */
    private Instant lockedAt;

    private Instant lastFailedAt;

    @Transient
    public boolean isLocked() {
        return lockedAt != null;
    }

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Spring Security authorities for a role. Kept here rather than in the
     * security config so the API and the UI read the same source.
     */
    public static Set<String> permissions(Role role) {
        return switch (role) {
            case OWNER -> Set.of("STAFF", "SETTINGS", "FINANCE", "CUSTOMERS", "NETWORK",
                    "OUTREACH", "SELL", "PRICING");
            case MANAGER -> Set.of("FINANCE", "CUSTOMERS", "NETWORK", "OUTREACH", "SELL", "PRICING");
            case ACCOUNTANT -> Set.of("FINANCE");
            // SELL lets support hand a customer a voucher, which they need for
            // day-to-day help. PRICING is withheld: setting package prices is a
            // commercial decision, not a support one.
            case SUPPORT -> Set.of("CUSTOMERS", "SELL");
        };
    }

    @Transient
    public Set<String> getPermissions() {
        return permissions(role);
    }
}
