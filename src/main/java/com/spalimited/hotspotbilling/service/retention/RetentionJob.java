package com.spalimited.hotspotbilling.service.retention;

import com.spalimited.hotspotbilling.service.HeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs the overnight thinking: who is drifting away, and who is not getting
 * what they pay for.
 *
 * <p>Both read signals that move over days rather than minutes, so nightly is
 * the right cadence — and doing it at 3am means the work lands when nobody is
 * waiting on the database.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RetentionJob {

    private final RetentionService retention;
    private final SpeedAuditService speedAudit;
    private final HeartbeatService heartbeats;

    @Scheduled(cron = "0 15 3 * * *")
    public void run() {
        heartbeats.stamp("retention");
        try {
            // Yesterday, not today: a session still running cannot be measured
            // half-way and recorded as slow.
            speedAudit.recordDay(LocalDate.now().minusDays(1));
        } catch (Exception e) {
            log.warn("Speed audit failed: {}", e.getMessage());
        }
        try {
            retention.scoreAll();
        } catch (Exception e) {
            log.warn("Retention scoring failed: {}", e.getMessage());
        }
    }
}
