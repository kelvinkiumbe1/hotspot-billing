package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VoucherService {

    /** No 0/O or 1/I so codes survive being read out loud or printed. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int DEFAULT_CODE_LENGTH = 8;
    public static final int MIN_CODE_LENGTH = 6;
    public static final int MAX_CODE_LENGTH = 16;
    /** At least this many random characters after any prefix, or codes become guessable. */
    private static final int MIN_RANDOM_CHARS = 4;

    private final VoucherRepository voucherRepository;
    private final MikrotikService mikrotikService;
    private final HotspotSettingsService hotspotSettings;
    private final SecureRandom random = new SecureRandom();

    /**
     * Invalidates vouchers that were printed but never used, once they pass
     * the age set in Hotspot settings. Runs nightly; a zero setting means
     * unused vouchers never expire.
     */
    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public void expireStaleUnusedVouchers() {
        int days = hotspotSettings.unusedVoucherExpiryDays();
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int expired = 0;
        for (Voucher v : voucherRepository.findByStatusAndCreatedAtBefore(Voucher.Status.UNUSED, cutoff)) {
            v.setStatus(Voucher.Status.EXPIRED);
            voucherRepository.save(v);
            try {
                mikrotikService.removeVoucher(v);
            } catch (Exception ignore) {
                // Router may be offline; the reconcile pass will drop it later.
            }
            expired++;
        }
        if (expired > 0) {
            log.info("Auto-expired {} unused voucher(s) older than {} days", expired, days);
        }
    }

    /**
     * Creates a voucher for the plan and provisions it on the router,
     * with the default code format (8 random characters).
     */
    @Transactional
    public Voucher issue(Plan plan, String phoneNumber) {
        return issue(plan, phoneNumber, null, DEFAULT_CODE_LENGTH);
    }

    /**
     * Creates a voucher whose code starts with an optional prefix (e.g.
     * "SPA") padded to the requested total length with random characters.
     */
    @Transactional
    public Voucher issue(Plan plan, String phoneNumber, String prefix, Integer length) {
        return issue(plan, phoneNumber, prefix, length, null);
    }

    @Transactional
    public Voucher issue(Plan plan, String phoneNumber, String prefix, Integer length, String createdBy) {
        Voucher voucher = Voucher.builder()
                .code(uniqueCode(normalizePrefix(prefix), normalizeLength(prefix, length)))
                .plan(plan)
                .phoneNumber(phoneNumber)
                .createdBy(createdBy)
                .build();
        startClockIfSold(voucher);
        voucher = voucherRepository.save(voucher);
        mikrotikService.provisionVoucher(voucher);
        log.info("Issued voucher {} for plan '{}'", voucher.getCode(), plan.getName());
        return voucher;
    }

    /**
     * A pass someone has just bought starts running now, not whenever they
     * first connect. Somebody who pays at nine for six hours expects it to
     * run to three, and a code that sits unused keeping its full value is an
     * invitation to buy cheap passes and hoard them.
     *
     * <p>Only for passes issued <em>to somebody</em>. Stock generated in a
     * batch for an agent to resell has no buyer yet, so its clock cannot have
     * started — otherwise inventory handed over on Monday is worthless by
     * Tuesday. Shelf life for unsold stock is a separate control
     * ({@code unusedVoucherExpiryDays}).
     */
    private void startClockIfSold(Voucher voucher) {
        if (voucher.getPhoneNumber() == null || voucher.getPhoneNumber().isBlank()) {
            return;
        }
        voucher.setExpiresAt(Instant.now().plus(voucher.getEffectiveDurationMinutes(), ChronoUnit.MINUTES));
    }

    /**
     * Creates a pay-per-minute voucher: an exact number of minutes rather
     * than a predefined plan duration.
     */
    @Transactional
    public Voucher issueCustom(Plan plan, String phoneNumber, int minutes) {
        return issueCustom(plan, phoneNumber, minutes, null, null);
    }

    @Transactional
    public Voucher issueCustom(Plan plan, String phoneNumber, int minutes, String prefix, Integer length) {
        return issueCustom(plan, phoneNumber, minutes, prefix, length, null);
    }

    @Transactional
    public Voucher issueCustom(Plan plan, String phoneNumber, int minutes, String prefix, Integer length, String createdBy) {
        if (minutes < 1) {
            throw new IllegalArgumentException("Minutes must be at least 1");
        }
        Voucher voucher = Voucher.builder()
                .code(uniqueCode(normalizePrefix(prefix), normalizeLength(prefix, length)))
                .plan(plan)
                .phoneNumber(phoneNumber)
                .customDurationMinutes(minutes)
                .createdBy(createdBy)
                .build();
        startClockIfSold(voucher);
        voucher = voucherRepository.save(voucher);
        mikrotikService.provisionVoucher(voucher);
        log.info("Issued custom {}-minute voucher {}", minutes, voucher.getCode());
        return voucher;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String cleaned = prefix.trim().toUpperCase();
        if (!cleaned.matches("[A-Z0-9]{1,12}")) {
            throw new IllegalArgumentException("Prefix must be 1-12 letters or digits");
        }
        return cleaned;
    }

    private int normalizeLength(String prefix, Integer length) {
        int total = length != null ? length : DEFAULT_CODE_LENGTH;
        if (total < MIN_CODE_LENGTH || total > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "Code length must be between " + MIN_CODE_LENGTH + " and " + MAX_CODE_LENGTH);
        }
        int prefixLength = prefix == null || prefix.isBlank() ? 0 : prefix.trim().length();
        if (total - prefixLength < MIN_RANDOM_CHARS) {
            throw new IllegalArgumentException(
                    "Code length must leave at least " + MIN_RANDOM_CHARS + " random characters after the prefix");
        }
        return total;
    }

    /**
     * Marks a voucher active the first time the customer uses it; the
     * router enforces the actual cutoff via limit-uptime, this mirrors it
     * for reporting.
     */
    @Transactional
    public Voucher activate(String code) {
        Voucher voucher = voucherRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown voucher code"));
        if (voucher.getStatus() != Voucher.Status.UNUSED) {
            throw new IllegalStateException("Voucher already " + voucher.getStatus().name().toLowerCase());
        }
        voucher.setStatus(Voucher.Status.ACTIVE);
        voucher.setActivatedAt(Instant.now());
        // A pass sold to somebody already has its deadline, set when they paid.
        // Only stock that had no buyer starts counting on first use.
        if (voucher.getExpiresAt() == null) {
            voucher.setExpiresAt(Instant.now().plus(voucher.getEffectiveDurationMinutes(), ChronoUnit.MINUTES));
        }
        return voucher;
    }

    /**
     * Cuts off passes whose wall-clock deadline has gone by.
     *
     * <p>This has to exist for the deadline to mean anything. The router is
     * told {@code limit-uptime}, which counts connected time — so a customer
     * who buys six hours at nine, uses one, and comes back at eight in the
     * evening still has five hours of credit sitting on the router. Marking
     * the pass expired in the database without removing it there would leave
     * them online, and the revenue audit would then quite correctly start
     * reporting the system's own passes as expired-but-still-connected.
     */
    @Transactional
    public int expirePastDeadline() {
        List<Voucher> due = voucherRepository.findByStatusInAndExpiresAtBefore(
                List.of(Voucher.Status.UNUSED, Voucher.Status.ACTIVE), Instant.now());
        int closed = 0;
        for (Voucher v : due) {
            v.setStatus(Voucher.Status.EXPIRED);
            voucherRepository.save(v);
            try {
                mikrotikService.removeVoucher(v);
            } catch (Exception e) {
                // The database is the record; a router we cannot reach now
                // gets tidied by the next pass, or by the recovery reconcile.
                log.debug("Could not remove expired voucher {} from the router: {}",
                        v.getCode(), e.getMessage());
            }
            closed++;
        }
        if (closed > 0) {
            log.info("Expired {} pass(es) that ran past their time", closed);
        }
        return closed;
    }

    private String uniqueCode(String prefix, int totalLength) {
        String code;
        do {
            StringBuilder sb = new StringBuilder(totalLength).append(prefix);
            while (sb.length() < totalLength) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = sb.toString();
        } while (voucherRepository.existsByCode(code));
        return code;
    }
}
