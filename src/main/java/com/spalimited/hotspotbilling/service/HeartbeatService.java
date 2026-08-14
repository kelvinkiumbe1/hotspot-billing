package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.JobHeartbeat;
import com.spalimited.hotspotbilling.repository.JobHeartbeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records that a scheduled job ran.
 *
 * <p>Deliberately its own transaction: a job that stamps its heartbeat and then
 * fails should still have left the stamp, otherwise the rollback erases the
 * very evidence that it tried. And a stamp that cannot be written must never
 * take the job down with it — this is instrumentation, not business logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HeartbeatService {

    private final JobHeartbeatRepository heartbeats;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stamp(String jobName) {
        stamp(jobName, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stamp(String jobName, String note) {
        try {
            JobHeartbeat beat = heartbeats.findById(jobName)
                    .orElseGet(() -> JobHeartbeat.builder().jobName(jobName).build());
            beat.setLastRunAt(Instant.now());
            beat.setLastNote(note == null || note.length() <= 300 ? note : note.substring(0, 300));
            heartbeats.save(beat);
        } catch (Exception e) {
            log.debug("Could not stamp heartbeat for {}: {}", jobName, e.getMessage());
        }
    }
}
