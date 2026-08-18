package com.spalimited.hotspotbilling.service.radius;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tidies up after routers that stopped talking.
 *
 * <p>A NAS that reboots or loses power never sends an Accounting-Stop, so
 * without this the session stays open forever: the customer looks permanently
 * connected, their device allowance stays spent, and nothing reconciles. That
 * is not an edge case — it is how most sessions end in the field.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RadiusJanitorJob {

    private final RadiusAccountingService accounting;
    private final RadiusSettingsService settings;
    private final com.spalimited.hotspotbilling.service.HeartbeatService heartbeats;

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void run() {
        if (!settings.get().isEnabled()) {
            return;
        }
        heartbeats.stamp("radius-janitor");
        try {
            accounting.closeAbandoned(settings.get().getInterimSeconds());
        } catch (Exception e) {
            log.warn("Could not close abandoned RADIUS sessions: {}", e.getMessage());
        }
    }
}
