package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A comment (optionally with a photo) on a maintenance task. Technicians
 * post progress updates and site photos from the field; admins reply from
 * the maintenance calendar.
 */
@Entity
@Table(name = "task_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The maintenance event (task) this note belongs to. */
    @Column(nullable = false)
    private Long eventId;

    /** Username of whoever wrote the note. */
    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private boolean fromAdmin;

    @Column(length = 2000)
    private String body;

    /** Stored filename under the upload dir; served at /api/uploads/{name}. */
    private String photoFilename;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
