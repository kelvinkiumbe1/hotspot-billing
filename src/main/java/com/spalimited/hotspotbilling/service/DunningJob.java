package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives failed-payment recovery: every few minutes it re-prompts subscribers
 * whose auto-renewal wasn't paid, on an escalating schedule, until they pay or
 * the attempts run out. The heavy lifting lives in {@link SubscriptionService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DunningJob {

    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    public void run() {
        try {
            subscriptionService.runDunning();
        } catch (Exception e) {
            log.warn("Dunning run failed: {}", e.getMessage());
        }
    }
}
