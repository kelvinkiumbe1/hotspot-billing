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
     * What is left on a pass, from the customer's point of view.
     *
     * <p>Two separate limits run at once and either can end the pass, so the
     * honest answer is whichever runs out first: the connect-time the router
     * counts, and the wall-clock deadline set when they paid. Reporting only
     * one of them would tell somebody they had four hours left an hour before
     * their pass died.
     */
    public record PassStatus(String code, String planName, String status,
                             long minutesLeft, Instant expiresAt,
                             long usedMb, Integer capMb, Long mbLeft) {
    }

    @Transactional(readOnly = true)
    public Voucher byCode(String code) {
        return voucherRepository.findByCode(code == null ? "" : code.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Unknown voucher code"));
    }

    @Transactional(readOnly = true)
    public PassStatus statusOf(Voucher v) {
        long byUptime = v.getRemainingSeconds() / 60;
        long minutesLeft = byUptime;
        if (v.getExpiresAt() != null) {
            long byClock = Math.max(0, ChronoUnit.MINUTES.between(Instant.now(), v.getExpiresAt()));
            minutesLeft = Math.min(byUptime, byClock);
        }
        if (v.getStatus() == Voucher.Status.EXPIRED) {
            minutesLeft = 0;
        }

        long usedMb = v.getUsedBytes() / 1_048_576L;
        Integer capMb = v.getPlan() == null ? null : v.getPlan().getDataLimitMb();
        Long mbLeft = capMb == null || capMb <= 0 ? null : Math.max(0, capMb - usedMb);

        return new PassStatus(v.getCode(),
                v.getPlan() == null ? null : v.getPlan().getName(),
                v.getStatus().name(), minutesLeft, v.getExpiresAt(), usedMb, capMb, mbLeft);
    }

    /**
     * Signs out whatever device is on the code, so it can be used on another.
     * The pass keeps its remaining time — the router bills uptime, not logins.
     */
    @Transactional
    public boolean signOutDevices(Voucher v) {
        if (v.getStatus() == Voucher.Status.EXPIRED) {
            throw new IllegalStateException("That pass has already finished");
        }
        boolean released = mikrotikService.kickSessions(v);
        if (released && v.getBoundMac() != null) {
            // Forget which device this was, so the binding sweep attaches it to
            // whichever one is used next. Leaving it would let the customer
            // sign out and then be refused everywhere — one device at a time is
            // the rule, not one device forever.
            v.setBoundMac(null);
            voucherRepository.save(v);
        }
        return released;
    }

    /**
     * Reissues a pass under a fresh code, carrying over what is left of it.
     *
     * <p>This is what a customer needs when their code has got out — shared
     * around a hostel, read over someone's shoulder, sent to the wrong person.
     * The old code is removed from the router and stops working immediately,
     * including for whoever is using it at that moment; the new one continues
     * with exactly the time and data already paid for.
     *
     * <p>The same row is kept rather than issuing a second voucher, so the
     * payment it came from, the usage recorded against it and the audit trail
     * all stay attached to one pass. What changes is the code.
     */
    @Transactional
    public Voucher reissueUnderNewCode(Voucher v) {
        if (v.getStatus() == Voucher.Status.EXPIRED || v.isExhausted()) {
            throw new IllegalStateException("That pass has already finished — buy a new one");
        }
        String oldCode = v.getCode();
        int minutesLeft = (int) statusOf(v).minutesLeft();
        if (minutesLeft <= 0) {
            throw new IllegalStateException("That pass has no time left on it");
        }

        // Remove the old one first. If the new code were added first and the
        // removal then failed, both codes would work at once — which is the
        // exact problem the customer came here to fix.
        try {
            mikrotikService.removeVoucher(v);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not reach the router to cancel the old code, so nothing has changed. "
                            + "Try again in a moment.", e);
        }

        v.setCode(uniqueCode("", oldCode.length()));
        // The replacement starts from zero on the router, so its allowance is
        // what remains — and the usage already recorded stays on the row.
        v.setRouterUptimeSeconds(0);
        Voucher saved = voucherRepository.save(v);
        mikrotikService.provisionVoucher(saved, minutesLeft);
        log.info("Reissued pass {} as {} with {} minute(s) remaining", oldCode, saved.getCode(), minutesLeft);
        return saved;
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
