package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Copies every router's configuration off it, nightly.
 *
 * <p>At 02:40, which is after the nightly database backup and before anybody is
 * awake to be changing anything. The exact minute is only chosen to not land on
 * the same instant as every other cron in this codebase.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouterConfigBackupJob {

    private final RouterConfigBackupService backupService;

    @Scheduled(cron = "0 40 2 * * *")
    public void nightly() {
        Map<String, Object> result = backupService.backupAll();
        @SuppressWarnings("unchecked")
        List<String> failed = (List<String>) result.get("failed");
        log.info("Router config backup: {} reached, {} changed, {} failed",
                result.get("backedUp"), result.get("changed"), failed.size());
        if (!failed.isEmpty()) {
            // Named, because "3 failed" sends somebody to the admin to find out
            // which and the log is where they are already looking.
            log.warn("Could not back up: {}", String.join(", ", failed));
        }
    }
}
