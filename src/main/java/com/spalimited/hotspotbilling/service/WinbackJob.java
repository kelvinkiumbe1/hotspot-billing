package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the win-back series: a few times a day it sends the next due
 * re-engagement message to customers who have stayed lapsed, escalating over
 * a few weeks and stopping the moment they return. Logic lives in
 * {@link SubscriptionService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WinbackJob {

    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelay = 21_600_000, initialDelay = 300_000)
    public void run() {
        try {
            subscriptionService.runWinback();
        } catch (Exception e) {
            log.warn("Win-back run failed: {}", e.getMessage());
        }
    }
}
