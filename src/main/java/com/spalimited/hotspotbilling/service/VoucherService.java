package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
    private final SecureRandom random = new SecureRandom();

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
        Voucher voucher = Voucher.builder()
                .code(uniqueCode(normalizePrefix(prefix), normalizeLength(prefix, length)))
                .plan(plan)
                .phoneNumber(phoneNumber)
                .build();
        voucher = voucherRepository.save(voucher);
        mikrotikService.provisionVoucher(voucher);
        log.info("Issued voucher {} for plan '{}'", voucher.getCode(), plan.getName());
        return voucher;
    }

    /**
     * Creates a pay-per-minute voucher: the customer bought an exact number
     * of minutes rather than a predefined plan duration.
     */
    @Transactional
    public Voucher issueCustom(Plan plan, String phoneNumber, int minutes) {
        Voucher voucher = Voucher.builder()
                .code(uniqueCode("", DEFAULT_CODE_LENGTH))
                .plan(plan)
                .phoneNumber(phoneNumber)
                .customDurationMinutes(minutes)
                .build();
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
        voucher.setExpiresAt(Instant.now().plus(voucher.getEffectiveDurationMinutes(), ChronoUnit.MINUTES));
        return voucher;
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
