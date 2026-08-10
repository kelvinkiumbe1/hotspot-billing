package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Runtime-editable security policy, one row (id = 1). Lets an owner control
 * passkey enforcement, session length and lockout from the Settings page
 * instead of environment variables.
 */
@Entity
@Table(name = "security_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecuritySettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** When true, staff with no passkey must enrol one on their next sign-in. */
    @Builder.Default
    @Column(nullable = false)
    private boolean requirePasskeys = false;

    /** How long a signed-in session lasts before it must sign in again. */
    @Builder.Default
    @Column(nullable = false)
    private int sessionTimeoutHours = 12;

    /** Wrong sign-ins before an account is locked until an owner resets it. */
    @Builder.Default
    @Column(nullable = false)
    private int maxLoginAttempts = 5;
}
