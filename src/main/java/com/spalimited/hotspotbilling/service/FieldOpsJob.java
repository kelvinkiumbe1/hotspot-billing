package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chases field work that has gone quiet: technicians sitting on jobs they
 * haven't updated, and jobs nobody has taken. Also sends the start-of-day job
 * list, which is why this runs every quarter of an hour rather than nightly.
 * Logic lives in {@link FieldOpsService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FieldOpsJob {

    private final FieldOpsService fieldOps;
    private final HeartbeatService heartbeats;

    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    public void run() {
        heartbeats.stamp("field-ops");
        try {
            fieldOps.runSweep();
        } catch (Exception e) {
            log.warn("Field ops sweep failed: {}", e.getMessage());
        }
    }
}
