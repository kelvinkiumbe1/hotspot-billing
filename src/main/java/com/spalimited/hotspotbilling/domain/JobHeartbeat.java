package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The last time a scheduled job actually ran.
 *
 * <p>A Spring scheduler that has stopped looks exactly like one with nothing
 * to do — no error, no log line, just an absence. Since these jobs are what
 * suspend non-payers, retry failed renewals and reconcile lost callbacks, that
 * absence is expensive and silent. Stamping each run makes it visible.
 */
@Entity
@Table(name = "job_heartbeats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobHeartbeat {

    @Id
    @Column(length = 64)
    private String jobName;

    @Column(nullable = false)
    private Instant lastRunAt;

    @Column(length = 300)
    private String lastNote;
}
