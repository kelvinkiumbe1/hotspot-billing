package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An outbound webhook: platform events matching {@code events} are POSTed to
 * {@code url}, each body signed with {@code secret} (HMAC-SHA256) so the
 * receiver can verify it came from us.
 */
@Entity
@Table(name = "webhooks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String secret;

    /** Comma-separated event names this endpoint wants. */
    @Column(nullable = false)
    private String events;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    /** HTTP status of the most recent delivery, for a quick health read. */
    private Integer lastStatus;

    private Instant lastAttemptAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
