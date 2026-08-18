package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One live or finished login, as the router reported it.
 *
 * <p>The same information the router poller collects today, except volunteered
 * rather than asked for every two minutes — which closes the gap where someone
 * connects and disconnects between two polls and is never recorded at all.
 */
@Entity
@Table(name = "radius_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadiusSession {

    public enum Kind { HOTSPOT, PPPOE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The NAS's own id for this session; unique per NAS rather than globally. */
    @Column(nullable = false, length = 120)
    private String acctSessionId;

    @Column(nullable = false, length = 64)
    private String nasAddress;

    @Column(nullable = false, length = 120)
    private String username;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind = Kind.HOTSPOT;

    private Long voucherId;

    private Long subscriberId;

    @Column(length = 64)
    private String framedIp;

    /** The client's MAC, in NAS terms. */
    @Column(length = 64)
    private String callingStation;

    @Column(length = 64)
    private String calledStation;

    @Column(length = 120)
    private String nasPortId;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant lastUpdateAt;

    private Instant stoppedAt;

    @Column(length = 64)
    private String terminateCause;

    @Builder.Default
    @Column(nullable = false)
    private long inOctets = 0;

    @Builder.Default
    @Column(nullable = false)
    private long outOctets = 0;

    @Builder.Default
    @Column(nullable = false)
    private long sessionSeconds = 0;

    /**
     * How much of this session has already been folded into the voucher.
     *
     * <p>Accounting packets are resent when a reply is lost, and a NAS that
     * comes back from a reboot will replay them. Without a high-water mark, a
     * duplicate Stop would charge a customer for their session a second time.
     */
    @Builder.Default
    @Column(nullable = false)
    private long appliedOctets = 0;

    @Builder.Default
    @Column(nullable = false)
    private long appliedSeconds = 0;

    @Transient
    public boolean isOpen() {
        return stoppedAt == null;
    }
}
