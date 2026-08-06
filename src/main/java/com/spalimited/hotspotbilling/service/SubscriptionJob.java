package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep of PPPoE subscriptions: suspends lapsed accounts on the
 * router and sends expiry-reminder SMS three days out.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionJob {

    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void run() {
        try {
            subscriptionService.sweep();
        } catch (Exception e) {
            log.warn("Subscription sweep failed: {}", e.getMessage());
        }
    }
}
