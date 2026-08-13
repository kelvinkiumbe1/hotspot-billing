package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Fair-use (FUP) enforcement. Plans can cap data and, once a pass crosses that
 * cap, throttle it, block it, or just notify the customer. The router monitor
 * calls {@link #enforce} each poll with the pass's live cumulative usage; this
 * applies the plan's chosen action exactly once per pass.
 *
 * <p>Router-side actions are attempted through {@link MikrotikService}; if the
 * router is unreachable the pass is left un-marked so the next poll retries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FupService {

    private static final long MB_BYTES = 1024L * 1024L;

    private final MikrotikService mikrotikService;
    private final NotificationService notificationService;
    private final PortalSettingsService portalSettingsService;
    private final VoucherRepository vouchers;

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

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
