package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A long-lived personal access token for the REST API. Resolves to the
 * staff member who created it, so a request bearing it carries that person's
 * role and permissions.
 */
@Entity
@Table(name = "api_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Long staffUserId;

    private String createdBy;

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
