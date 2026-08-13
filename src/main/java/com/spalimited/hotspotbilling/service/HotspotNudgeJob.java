package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Hotspot re-purchase nudge: when a customer's WiFi pass is minutes from
 * running out, message them "your WiFi is almost up — buy more". On WhatsApp
 * their reply drops straight into the self-service bot, so they can top up
 * without logging back into the captive portal.
 *
 * <p>This is the outbound counterpart to the bot's inbound flow. It complements
 * the subscription automation (renewal reminders / receipts / suspension in
 * {@link SubscriptionService}); those cover home-internet subscribers, this one
 * covers pay-as-you-go hotspot passes, which have no scheduled expiry SMS.
 *
 * <p>Fires at most once per voucher (guarded by {@code nudgedAt}) and only when
 * the voucher carries a phone number to reach. Runs frequently because hotspot
 * passes are short — a customer on a 1-hour pass must be caught inside that hour.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HotspotNudgeJob {

    private final VoucherRepository vouchers;
    private final NotificationService notificationService;
    private final PortalSettingsService portalSettingsService;

    /** Turn the nudge off entirely without touching templates. */
    @Value("${hotspot.nudge.enabled:true}")
    private boolean enabled;

    /** How many minutes before a pass runs out to send the nudge. */
    @Value("${hotspot.nudge.lead-minutes:10}")
    private long leadMinutes;

    /** Turn the data-bundle top-up nudge off without touching templates. */
    @Value("${hotspot.data-nudge.enabled:true}")
    private boolean dataNudgeEnabled;

    /** Percent of a data-bundle plan's cap that triggers the top-up nudge. */
    @Value("${hotspot.data-nudge.threshold-percent:80}")
    private int dataThresholdPercent;

    private static final long MB_BYTES = 1024L * 1024L;

    /** Runs every 3 minutes so short passes are caught inside their window. */
    @Scheduled(fixedDelay = 180_000)
    public void run() {
        if (!enabled || leadMinutes <= 0) {
            return;
        }
        Instant now = Instant.now();
        Instant cutoff = now.plus(leadMinutes, ChronoUnit.MINUTES);
        List<Voucher> due = vouchers.findByStatusAndNudgedAtIsNullAndExpiresAtBetween(
                Voucher.Status.ACTIVE, now, cutoff);
        if (due.isEmpty()) {
            return;
        }
        String business = portalSettingsService.settings().getBusinessName();
        for (Voucher v : due) {
            try {
                nudge(v, business, now);
            } catch (Exception e) {
                log.warn("Hotspot nudge failed for voucher {}: {}", v.getId(), e.getMessage());
            }
        }
    }

    /**
     * Nudges data-bundle customers who've burned through most of their cap.
     * Runs less often than the time nudge — data drains over hours, not minutes.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 90_000)
    public void runDataNudge() {
        if (!dataNudgeEnabled || dataThresholdPercent <= 0) {
            return;
        }
        long threshold = (long) dataThresholdPercent * MB_BYTES;
        List<Voucher> due = vouchers.findDataNudgeCandidates(Voucher.Status.ACTIVE, threshold);
        if (due.isEmpty()) {
            return;
        }
        String business = portalSettingsService.settings().getBusinessName();
        for (Voucher v : due) {
            try {
                dataNudge(v, business);
            } catch (Exception e) {
                log.warn("Data nudge failed for voucher {}: {}", v.getId(), e.getMessage());
            }
        }
    }

    /** Sends one data top-up nudge and stamps the voucher so it can't repeat. */
    private void dataNudge(Voucher voucher, String business) {
        Voucher v = vouchers.findById(voucher.getId()).orElse(null);
        if (v == null || v.getDataNudgedAt() != null || v.getStatus() != Voucher.Status.ACTIVE) {
            return;
        }
        String phone = v.getPhoneNumber();
        Integer capMb = v.getPlan() != null ? v.getPlan().getDataLimitMb() : null;
        if (phone == null || phone.isBlank() || capMb == null || capMb <= 0) {
            return;
        }
        long usedMb = v.getUsedBytes() / MB_BYTES;
        notificationService.send(
                NotificationTemplate.Key.HOTSPOT_DATA_NUDGE, phone,
                Map.of(
                        "business", business == null ? "" : business,
                        "usedMb", String.valueOf(usedMb),
                        "capMb", String.valueOf(capMb)));
        v.setDataNudgedAt(Instant.now());
        vouchers.save(v);
        log.info("Sent hotspot data top-up nudge for voucher {} ({}MB of {}MB)", v.getId(), usedMb, capMb);
    }

    /** Sends one nudge and stamps the voucher so it can't repeat. */
    private void nudge(Voucher voucher, String business, Instant now) {
        // Re-read inside the transaction and re-check the guard so two overlapping
        // sweeps can't both send for the same voucher.
        Voucher v = vouchers.findById(voucher.getId()).orElse(null);
        if (v == null || v.getNudgedAt() != null || v.getStatus() != Voucher.Status.ACTIVE) {
            return;
        }
        String phone = v.getPhoneNumber();
        if (phone == null || phone.isBlank() || v.getExpiresAt() == null) {
            return;
        }
        long minutesLeft = Math.max(1, ChronoUnit.MINUTES.between(now, v.getExpiresAt()));
        notificationService.send(
                NotificationTemplate.Key.HOTSPOT_EXPIRY_NUDGE, phone,
                Map.of(
                        "business", business == null ? "" : business,
                        "minutes", String.valueOf(minutesLeft)));
        v.setNudgedAt(now);
        vouchers.save(v);
        log.info("Sent hotspot re-purchase nudge for voucher {} ({} min left)", v.getId(), minutesLeft);
    }
}
