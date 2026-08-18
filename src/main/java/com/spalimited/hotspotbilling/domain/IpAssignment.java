package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One address that is not free.
 *
 * <p>Absence is what "free" means here, so releasing an address deletes the row
 * rather than flagging it. A released-but-present row is the state where a
 * query forgets the flag and hands out a live customer address.
 */
@Entity
@Table(name = "ip_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IpAssignment {

    /** Why this address is not available. */
    public enum Kind {
        /** Given to a customer or a device. */
        ASSIGNED,
        /** Held back deliberately — a DHCP range, a printer, a plan. */
        RESERVED,
        /** The router itself. Reserved on the subnet being created. */
        GATEWAY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long subnetId;

    @Column(nullable = false, length = 64)
    private String address;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind = Kind.ASSIGNED;

    private Long subscriberId;

    private Long deviceId;

    @Column(length = 64)
    private String macAddress;

    @Column(length = 120)
    private String hostname;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false)
    private Instant assignedAt;

    @Column(length = 120)
    private String assignedBy;

    @PrePersist
    void onCreate() {
        if (assignedAt == null) {
            assignedAt = Instant.now();
        }
    }
}
