package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Checks capped customers against what they have used, and puts them back when
 * the month turns over.
 *
 * <p>Deliberately a job rather than something the RADIUS accounting path does
 * inline. Enforcing a cap means talking to a router, and a router that is slow
 * or down would then stall the handling of an accounting packet -- on a busy NAS
 * that is a queue building up behind a device that cannot answer, and the
 * symptom would be lost accounting rather than anything pointing back here.
 * Recording the bytes is fast and happens inline; acting on them is not and does
 * not.
 *
 * <p>Every ten minutes. A monthly allowance does not need finer granularity than
 * that, and a customer who blows through their cap at 14:02 being throttled at
 * 14:10 is not a difference anybody can perceive.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriberFupJob {

    private final SubscriberRepository subscribers;
    private final FupService fupService;
    private final SubscriberUsageService subscriberUsage;

    @Scheduled(fixedDelay = 600_000L, initialDelay = 120_000L)
    public void sweep() {
        int changed = 0;
        for (Subscriber sub : subscribers.findByDataCapMbIsNotNullOrFupAppliedAtIsNotNull()) {
            try {
                if (fupService.reviewSubscriber(sub)) {
                    changed++;
                }
            } catch (Exception e) {
                // One unreachable router must not stop the sweep for everybody
                // else -- including the customers who are owed their speed back.
                log.warn("Fair-use review failed for subscriber {}: {}", sub.getId(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("Fair-use sweep changed {} subscriber(s)", changed);
        }
    }

    /** Housekeeping on the usage table. Monthly; see SubscriberUsageService.prune. */
    @Scheduled(cron = "0 30 3 2 * *")
    public void pruneUsage() {
        subscriberUsage.prune();
    }
}
