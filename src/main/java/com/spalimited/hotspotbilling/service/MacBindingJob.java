package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically locks freshly-used vouchers to the device that used them.
 * A no-op unless both MikroTik integration and MAC binding are enabled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MacBindingJob {

    private final MikrotikService mikrotikService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        try {
            mikrotikService.syncMacBindings();
        } catch (Exception e) {
            log.warn("MAC binding sync failed: {}", e.getMessage());
        }
    }
}
