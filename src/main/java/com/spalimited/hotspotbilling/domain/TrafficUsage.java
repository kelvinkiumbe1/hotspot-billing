package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One accumulating row of hotspot traffic per (hour, router, user). Written by
 * the monitor job from the router's live session counters and read by the
 * analytics reports. See V18__traffic_usage.sql for the capture rationale.
 */
@Entity
@Table(name = "traffic_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrafficUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hour bucket (truncated to the hour) the traffic was observed in. */
    @Column(name = "bucket_hour", nullable = false)
    private Instant bucketHour;

    @Column(name = "router_id", nullable = false)
    private Long routerId;

    /** Hotspot username = voucher code — the customer identity. */
    @Column(name = "user_key", nullable = false, length = 128)
    private String userKey;

    /** Plan the traffic is attributed to, when the user's voucher is known. */
    @Column(name = "plan_id")
    private Long planId;

    @Builder.Default
    @Column(name = "bytes_up", nullable = false)
    private long bytesUp = 0;

    @Builder.Default
    @Column(name = "bytes_down", nullable = false)
    private long bytesDown = 0;
}
