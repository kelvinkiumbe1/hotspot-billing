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

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Set on the account seeded from application.properties at first boot. */
    @Builder.Default
    @Column(nullable = false)
    private boolean seeded = false;

    private String createdBy;

    private Instant lastLoginAt;

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
