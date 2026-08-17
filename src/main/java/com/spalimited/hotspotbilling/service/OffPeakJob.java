package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opens the off-peak offer when the quiet hours start and closes it when they
 * end. Runs a minute past each hour, which is as fine-grained as an hourly
 * window needs. Logic lives in {@link OffPeakService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OffPeakJob {

    private final OffPeakService offPeak;

    @Scheduled(cron = "0 1 * * * *")
    public void run() {
        try {
            offPeak.sync();
        } catch (Exception e) {
            log.warn("Off-peak sync failed: {}", e.getMessage());
        }
    }
}
