package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Whether this system answers logins for other people's hardware, and how. */
@Entity
@Table(name = "radius_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadiusSettings {

    @Id
    @Builder.Default
    private Long id = 1L;

    /**
     * Off until an operator turns it on. Opening two UDP ports is not something
     * to do to a deployment that never asked for it.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    @Builder.Default
    @Column(nullable = false)
    private int authPort = 1812;

    @Builder.Default
    @Column(nullable = false)
    private int acctPort = 1813;

    /**
     * How often a NAS should report on a live session. Bounds how much time can
     * be lost when a router dies without sending a Stop — which is the ordinary
     * way sessions end in the field, not the exception.
     */
    @Builder.Default
    @Column(nullable = false)
    private int interimSeconds = 300;

    /** Cut a customer off the moment their pass runs out, rather than waiting. */
    @Builder.Default
    @Column(nullable = false)
    private boolean disconnectEnabled = true;

    private Instant updatedAt;

    @Column(length = 120)
    private String updatedBy;

    @PreUpdate
    @PrePersist
    void stamp() {
        updatedAt = Instant.now();
    }
}
