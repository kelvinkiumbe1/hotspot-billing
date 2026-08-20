package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Fair-use (FUP) enforcement. Plans can cap data and, once a pass crosses that
 * cap, throttle it, block it, or just notify the customer. The router monitor
 * calls {@link #enforce} each poll with the pass's live cumulative usage; this
 * applies the plan's chosen action exactly once per pass.
 *
 * <p>Router-side actions are attempted through {@link MikrotikService}; if the
 * router is unreachable the pass is left un-marked so the next poll retries.
 *
 * <h2>Subscribers</h2>
 *
 * <p>Monthly customers work the same way but arrive from the other end. A pass
 * has a plan and a lifetime measured in hours, so its cap is on the plan and is
 * applied once and forgotten. A fibre line has no plan at all -- the cap is on
 * the subscriber -- and it lives for years, so its cap resets every month and
 * whatever was done at the cap has to be undone again. That undoing is the part
 * worth being careful about: a customer left throttled into a month they have
 * paid for is a complaint, and it is invisible from the admin unless somebody
 * looks. So the sweep restores as well as applies, and decides which by
 * comparing cap periods rather than by trusting a job to have run at midnight.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FupService {

    private static final long MB_BYTES = 1024L * 1024L;

    private final MikrotikService mikrotikService;
    private final SubscriberProvisioningService provisioning;
    private final NotificationService notificationService;
    private final PortalSettingsService portalSettingsService;
    private final VoucherRepository vouchers;
    private final SubscriberRepository subscribers;
    private final SubscriberUsageService subscriberUsage;

    /** Applies the plan's FUP action to a pass that has crossed its cap. */
    public void enforce(Router router, Voucher voucher) {
        Plan plan = voucher.getPlan();
        if (plan == null || !plan.isFupOn() || voucher.getFupAppliedAt() != null) {
            return;
        }
        long capBytes = (long) plan.getFupLimitMb() * MB_BYTES;
        if (voucher.getUsedBytes() < capBytes) {
            return;
        }
        Plan.FupAction action = plan.getFupAction() != null ? plan.getFupAction() : Plan.FupAction.NOTIFY;
        boolean applied = switch (action) {
            case THROTTLE -> throttle(router, voucher, plan);
            case BLOCK -> block(router, voucher);
            case NOTIFY -> true; // message only
        };
        if (!applied) {
            return; // router call failed — retry on the next poll
        }
        notifyCustomer(voucher, plan);
        voucher.setFupAppliedAt(Instant.now());
        vouchers.save(voucher);
        log.info("Applied FUP {} to voucher {} ({}MB cap)", action, voucher.getId(), plan.getFupLimitMb());
    }

    private boolean throttle(Router router, Voucher voucher, Plan plan) {
        String rate = plan.getFupRate();
        if (rate == null || rate.isBlank()) {
            return true; // no rate configured — treat as notify-only
        }
        try {
            mikrotikService.setHotspotRate(router, voucher.getCode(), rate);
            return true;
        } catch (Exception e) {
            log.warn("FUP throttle failed for voucher {}: {}", voucher.getId(), e.getMessage());
            return false;
        }
    }

    private boolean block(Router router, Voucher voucher) {
        try {
            mikrotikService.removeVoucher(voucher);
            voucher.setStatus(Voucher.Status.EXPIRED);
            return true;
        } catch (Exception e) {
            log.warn("FUP block failed for voucher {}: {}", voucher.getId(), e.getMessage());
            return false;
        }
    }

    private void notifyCustomer(Voucher voucher, Plan plan) {
        if (voucher.getPhoneNumber() == null || voucher.getPhoneNumber().isBlank()) {
            return;
        }
        notificationService.send(NotificationTemplate.Key.FUP_NOTICE, voucher.getPhoneNumber(),
                Map.of("business", safe(portalSettingsService.settings().getBusinessName()),
                        "capMb", String.valueOf(plan.getFupLimitMb())));
    }

    // --- Monthly subscribers ---

    /**
     * Applies or lifts one subscriber's fair-use state.
     *
     * <p>Returns true if anything changed, so the sweep can log a number that
     * means something rather than the count of customers it looked at.
     */
    public boolean reviewSubscriber(Subscriber sub) {
        LocalDate cycle = subscriberUsage.cycleStart(subscriberUsage.today());

        // Applied in an earlier month: the allowance has rolled over, so give
        // them their speed back whether or not they are near the new cap.
        if (sub.getFupAppliedAt() != null && !cycle.equals(sub.getFupCycle())) {
            return liftSubscriber(sub, "the new month");
        }
        if (sub.getDataCapMb() == null || sub.getDataCapMb() <= 0) {
            // The cap was removed while they were throttled.
            return sub.getFupAppliedAt() != null && liftSubscriber(sub, "the cap being removed");
        }
        if (sub.getFupAppliedAt() != null) {
            return false; // already applied, this cycle
        }

        long capBytes = (long) sub.getDataCapMb() * MB_BYTES;
        if (subscriberUsage.thisCycleBytes(sub.getId()) < capBytes) {
            return false;
        }

        Plan.FupAction action = sub.getFupAction() != null ? sub.getFupAction() : Plan.FupAction.NOTIFY;
        boolean applied = switch (action) {
            case THROTTLE -> throttleSubscriber(sub);
            case BLOCK -> blockSubscriber(sub);
            case NOTIFY -> true;
        };
        if (!applied) {
            return false; // router unreachable -- retried on the next sweep
        }
        notifySubscriber(sub);
        sub.setFupAppliedAt(Instant.now());
        sub.setFupCycle(cycle);
        subscribers.save(sub);
        log.info("Applied FUP {} to subscriber {} ({}MB cap)", action, sub.getId(), sub.getDataCapMb());
        return true;
    }

    /** Puts a subscriber back to their paid-for speed and clears the mark. */
    private boolean liftSubscriber(Subscriber sub, String why) {
        Plan.FupAction was = sub.getFupAction() != null ? sub.getFupAction() : Plan.FupAction.NOTIFY;
        try {
            switch (was) {
                // Both restore paths are the subscriber's own stored settings, so
                // this cannot hand somebody the wrong speed even if the package
                // changed while they were throttled.
                case THROTTLE -> provisioning.setRate(sub, null);
                case BLOCK -> provisioning.setEnabled(sub, true);
                case NOTIFY -> { }
            }
        } catch (Exception e) {
            log.warn("Could not lift FUP for subscriber {}: {}", sub.getId(), e.getMessage());
            return false; // leave the mark so the next sweep tries again
        }
        sub.setFupAppliedAt(null);
        sub.setFupCycle(null);
        subscribers.save(sub);
        log.info("Lifted FUP on subscriber {} on {}", sub.getId(), why);
        return true;
    }

    private boolean throttleSubscriber(Subscriber sub) {
        if (sub.getFupRate() == null || sub.getFupRate().isBlank()) {
            return true; // no rate set -- behaves as notify-only
        }
        try {
            provisioning.setRate(sub, sub.getFupRate());
            return true;
        } catch (Exception e) {
            log.warn("FUP throttle failed for subscriber {}: {}", sub.getId(), e.getMessage());
            return false;
        }
    }

    private boolean blockSubscriber(Subscriber sub) {
        try {
            provisioning.setEnabled(sub, false);
            return true;
        } catch (Exception e) {
            log.warn("FUP block failed for subscriber {}: {}", sub.getId(), e.getMessage());
            return false;
        }
    }

    private void notifySubscriber(Subscriber sub) {
        if (sub.getPhoneNumber() == null || sub.getPhoneNumber().isBlank()) {
            return;
        }
        notificationService.send(NotificationTemplate.Key.FUP_NOTICE, sub.getPhoneNumber(),
                Map.of("business", safe(portalSettingsService.settings().getBusinessName()),
                        "capMb", String.valueOf(sub.getDataCapMb())));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
