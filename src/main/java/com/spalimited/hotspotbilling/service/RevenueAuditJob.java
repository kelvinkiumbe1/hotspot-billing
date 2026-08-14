package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs the revenue audit once a night, in the quiet hours — the sweep reads
 * every router, so it is deliberately kept away from the evening peak.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RevenueAuditJob {

    private final RevenueAuditService revenueAudit;

    @Scheduled(cron = "${revenue-audit.cron:0 30 2 * * *}")
    public void run() {
        if (!revenueAudit.settings().isEnabled()) {
            return;
        }
        try {
            Map<String, Object> summary = revenueAudit.sweep("system");
            log.info("Revenue audit: {} open issue(s), {} new, {} cleared",
                    summary.get("found"), summary.get("newFindings"), summary.get("closed"));
        } catch (Exception e) {
            log.warn("Revenue audit failed: {}", e.getMessage());
        }
    }
}
