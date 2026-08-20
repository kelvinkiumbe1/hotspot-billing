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

import java.time.Instant;

/**
 * The credentials a router or ONT must present before the ACS will speak to it.
 * Single row (id = 1).
 *
 * <p>One shared pair rather than one per device, because a CPE is configured
 * from a template before anybody knows which serial it will turn out to have.
 * TR-069 carries them as HTTP Basic on every request, so the transport has to be
 * HTTPS in the field — a shared password over plain HTTP is one tcpdump away
 * from being everybody's password.
 */
@Entity
@Table(name = "acs_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcsSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(length = 64)
    private String username;

    /** Encoded, never the password itself. Null means the ACS is shut. */
    @Column(name = "password_hash", length = 200)
    private String passwordHash;

    /**
     * Whether a serial nobody has seen before may register itself.
     *
     * <p>On for bringing in a new estate, off once it is in: with it off, a
     * correct password still cannot file a device that was never expected.
     */
    @Builder.Default
    @Column(name = "allow_unknown", nullable = false)
    private boolean allowUnknown = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
