package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps a suggested reply waiting on every unanswered ticket. Runs often and
 * shallow rather than rarely and deep: each draft costs the operator a model
 * call, so a rush of tickets spreads over a few minutes instead of emptying
 * their credit in one pass. Does nothing at all unless the operator has turned
 * drafting on. Logic lives in {@link TicketDraftService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketDraftJob {

    /** Per pass, i.e. per two minutes. */
    private static final int BATCH = 5;

    private final TicketDraftService drafts;

    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    public void run() {
        try {
            int n = drafts.draftPending(BATCH);
            if (n > 0) {
                log.info("Drafted replies for {} ticket(s)", n);
            }
        } catch (Exception e) {
            log.warn("Ticket draft pass failed: {}", e.getMessage());
        }
    }
}
