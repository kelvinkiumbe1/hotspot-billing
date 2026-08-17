package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pays agents their commission on the day and hour the operator chose, and
 * chases B2C requests Safaricom never answered. Runs hourly because the
 * schedule lives in the database — an operator changing pay day from the
 * admin cannot edit a cron expression. Logic lives in
 * {@link AgentPayoutService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentPayoutJob {

    private final AgentPayoutService payouts;
    private final HeartbeatService heartbeats;

    @Scheduled(cron = "0 5 * * * *")
    public void run() {
        heartbeats.stamp("agent-payouts");
        try {
            payouts.maybeRun();
        } catch (Exception e) {
            log.warn("Agent payout run failed: {}", e.getMessage());
        }
        try {
            payouts.flagStalePayouts();
        } catch (Exception e) {
            log.warn("Could not check for stale payouts: {}", e.getMessage());
        }
    }
}
