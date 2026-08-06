package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.persistence.Transient;
import lombok.*;

import java.time.Instant;

/**
 * A field technician account, managed by the admin from the Team page.
 * Technicians log in to the Field Connect app (/tech) with these
 * credentials; authentication is wired up in SecurityConfig.
 */
@Entity
@Table(name = "technicians")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Technician {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** Encoded with Spring's delegating password encoder (bcrypt). */
    @JsonIgnore
    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    private String phoneNumber;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** May generate vouchers in Field Connect. Null means allowed (legacy default). */
    private Boolean canVouchers;

    /** May create/manage PPPoE subscribers in Field Connect. Null means not allowed. */
    private Boolean canPppoe;

    @Transient
    public boolean isVouchersAllowed() {
        return canVouchers == null || canVouchers;
    }

    @Transient
    public boolean isPppoeAllowed() {
        return Boolean.TRUE.equals(canPppoe);
    }

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
