package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * One day of one subscriber's traffic. See V71__subscriber_usage.sql for why a
 * day rather than an hour, and why bytes rather than megabytes.
 */
@Entity
@Table(name = "subscriber_usage_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriberUsageDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscriber_id", nullable = false)
    private Long subscriberId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Builder.Default
    @Column(name = "bytes_up", nullable = false)
    private long bytesUp = 0;

    @Builder.Default
    @Column(name = "bytes_down", nullable = false)
    private long bytesDown = 0;

    @Transient
    public long getTotalBytes() {
        return bytesUp + bytesDown;
    }
}
