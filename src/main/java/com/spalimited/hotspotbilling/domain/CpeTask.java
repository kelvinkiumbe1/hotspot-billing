package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Something to tell a CPE next time it calls in.
 *
 * <p>A queue rather than a direct call, because TR-069 does not allow a direct
 * call: the ACS can only answer, never ask. An operator pressing "change the WiFi
 * password" creates one of these, and either the device's next Inform picks it up
 * or a connection request pokes the device into calling now.
 *
 * <p>That indirection is also why status matters more than it looks. PENDING and
 * DONE are different in a way a customer feels -- one means the password will
 * change, the other means it has.
 */
@Entity
@Table(name = "cpe_tasks",
        indexes = {
                @Index(name = "cpe_tasks_device_idx", columnList = "cpe_device_id"),
                @Index(name = "cpe_tasks_status_idx", columnList = "status"),
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CpeTask {

    public enum Kind { SET_PARAMETERS, GET_PARAMETERS, REBOOT, FACTORY_RESET, DOWNLOAD }

    /**
     * SENT is its own state and not a detail.
     *
     * <p>A task that was handed to a device and never acknowledged is a different
     * thing from one still waiting: the device may have applied it and lost the
     * session before replying. Collapsing the two would either replay changes a
     * customer already has or lose ones they do not.
     */
    public enum Status { PENDING, SENT, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cpe_device_id", nullable = false)
    private Long cpeDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Kind kind;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    /**
     * What the task carries, as JSON.
     *
     * <p>JSON rather than columns because the five kinds want different things --
     * a list of name/value pairs, a list of names, a URL and a size -- and five
     * sets of mostly-null columns would be worse than one field whose shape is
     * documented per kind in AcsService.
     */
    @Column(length = 4000)
    private String payload;

    /** What the device said if it refused: CWMP fault code and its message. */
    @Column(length = 500)
    private String fault;

    private Instant createdAt;

    private Instant sentAt;

    private Instant completedAt;

    private String requestedBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
