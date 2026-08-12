package com.spalimited.controlplane;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One ISP that has signed up. The registry the control plane owns: who exists,
 * on what subdomain, and where their provisioning got to. The tenant's actual
 * billing data lives in their own isolated stack, never here.
 */
@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    public enum Status {
        /** Signed up; the stack is being stood up. */
        PROVISIONING,
        /** Stack is up and the owner can log in. */
        ACTIVE,
        /** Provisioning failed; needs a retry or a look. */
        FAILED,
        /** Deliberately stopped (e.g. non-payment). */
        SUSPENDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** URL-safe short name, unique. Drives the subdomain and the stack name. */
    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(nullable = false, unique = true, length = 120)
    private String subdomain;

    @Column(nullable = false, length = 160)
    private String businessName;

    @Column(nullable = false, length = 160)
    private String ownerEmail;

    private String ownerName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PROVISIONING;

    /** Last provisioning message — the error when FAILED, or a progress note. */
    @Column(length = 1000)
    private String statusDetail;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant readyAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    @Transient
    public String getUrl() {
        return "https://" + subdomain;
    }
}
