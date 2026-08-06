package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A direct message between a technician and the admin — one channel per
 * technician, independent of any task. Supports an optional photo.
 */
@Entity
@Table(name = "direct_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectMessage {

    /** Username of the technician whose channel this message belongs to. */
    @Column(nullable = false)
    private String technician;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean fromAdmin;

    /** Username of whoever wrote the message. */
    @Column(nullable = false)
    private String author;

    @Column(length = 2000)
    private String body;

    /** Stored filename under the upload dir; served at /api/uploads/{name}. */
    private String photoFilename;

    /** Whether the recipient (admin or technician) has opened the channel since. */
    @Column(nullable = false)
    private boolean readByRecipient;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
