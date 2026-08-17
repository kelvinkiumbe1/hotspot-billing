package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The weekly capacity word, on the day and hour the operator chose. Hourly
 * because the schedule is set from the admin, not from a cron expression.
 * Logic lives in {@link CapacityService} — including the decision to say
 * nothing at all when nothing needs saying.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CapacityJob {

    private final CapacityService capacity;

    @Scheduled(cron = "0 20 * * * *")
    public void run() {
        try {
            capacity.maybeNotify();
        } catch (Exception e) {
            log.warn("Capacity check failed: {}", e.getMessage());
        }
    }
}
