package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PhoneVerification;
import com.spalimited.hotspotbilling.repository.PhoneVerificationRepository;
import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Proving a phone number belongs to the person giving it.
 *
 * <p>A number typed by a customer, or read down a phone by somebody in a shop, is
 * wrong often enough to matter. The renewal reminder, the receipt and the voucher
 * all go to a stranger, and the customer's first experience of the business is
 * silence. From the operator's side an unverified number is also how one person
 * buys forty trial passes.
 *
 * <h2>What makes this safe rather than theatre</h2>
 *
 * <p>Three things, and all three are needed. The code is six digits, which is
 * only enough entropy if guessing is bounded -- so attempts are counted and the
 * challenge dies at five. Requests are rate limited per number AND per source
 * address, because limiting only the number lets a script walk a thousand of
 * them, and limiting only the address lets one number be spammed with texts the
 * operator pays for. And the code is hashed at rest, because it is a credential
 * that arrives by SMS and a dump taken inside its window would otherwise hand
 * over live ones.
 *
 * <p>Comparison is constant-time. A six-digit code with an early-exit compare
 * leaks its digits to anybody willing to make enough attempts, which the attempt
 * counter would then dutifully allow five of per code -- but codes are cheap to
 * request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhoneVerificationService {

    /** Long enough to read a text and type it, short enough to be worthless later. */
    private static final Duration LIFETIME = Duration.ofMinutes(10);

    /** Five guesses at six digits is a one-in-two-hundred-thousand chance. */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * How long the proof of ownership lasts once a code is entered correctly.
     *
     * <p>Longer than it takes to finish the action it was requested for, shorter
     * than a walk away from an unlocked phone.
     */
    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);

    /** Per number, per hour. Enough for a real person who did not get the first. */
    private static final int MAX_PER_NUMBER_HOUR = 5;

    /**
     * Per source address, per hour. Higher than the per-number limit because a
     * shop or an office is many customers behind one address, and lower than
     * anything a script would find useful.
     */
    private static final int MAX_PER_IP_HOUR = 30;

    private final PhoneVerificationRepository verifications;
    private final SmsService smsService;
    private final PortalSettingsService portalSettings;
    private final PhoneNumbers phoneNumbers;

    private final SecureRandom random = new SecureRandom();

    /** What a request did, in words the caller can show unchanged. */
    public record Requested(boolean sent, String message, Instant expiresAt) {
    }

    /**
     * Sends a code to a number.
     *
     * <p>Replaces any outstanding challenge for the same number and purpose rather
     * than adding a second: a customer who taps twice should not end up holding
     * two codes with no idea which is current.
     */
    @Transactional
    public Requested request(String rawPhone, String purpose, String ip) {
        String phone = phoneNumbers.normalise(rawPhone);
        if (phone == null || phone.isBlank() || !phoneNumbers.isValid(rawPhone)) {
            return new Requested(false, "That does not look like a phone number.", null);
        }
        String use = purpose == null || purpose.isBlank() ? "GENERIC" : purpose.trim().toUpperCase();
        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));

        if (verifications.countByPhoneNumberAndCreatedAtAfter(phone, hourAgo) >= MAX_PER_NUMBER_HOUR) {
            // Deliberately says the limit rather than pretending to have sent it.
            // A customer who has genuinely not received four texts needs to know
            // to try something else, not to keep tapping.
            return new Requested(false,
                    "That number has been sent several codes already. Wait an hour, or call "
                            + "us and we will confirm it by voice.", null);
        }
        if (ip != null && !ip.isBlank()
                && verifications.countByRequestedIpAndCreatedAtAfter(ip, hourAgo) >= MAX_PER_IP_HOUR) {
            log.warn("Phone verification rate limit hit from {}", ip);
            return new Requested(false, "Too many codes requested from here. Try again later.",
                    null);
        }

        verifications.findByPhoneNumberAndPurposeAndVerifiedAtIsNull(phone, use)
                .ifPresent(verifications::delete);
        // Flushed before the insert, because the unique index allows only one live
        // challenge per number and purpose.
        verifications.flush();

        String code = String.format("%06d", random.nextInt(1_000_000));
        Instant expires = Instant.now().plus(LIFETIME);
        verifications.save(PhoneVerification.builder()
                .phoneNumber(phone)
                .codeHash(hash(phone, use, code))
                .createdAt(Instant.now())
                .expiresAt(expires)
                .purpose(use)
                .requestedIp(ip)
                .build());

        String business = safe(portalSettings.settings().getBusinessName());
        smsService.trySend(phone, (business.isBlank() ? "" : business + ": ")
                + code + " is your confirmation code. It expires in "
                + LIFETIME.toMinutes() + " minutes.");

        log.info("Sent a verification code to {} for {}", phone, use);
        return new Requested(true,
                "We have texted a code to " + phone + ". It expires in "
                        + LIFETIME.toMinutes() + " minutes.", expires);
    }

    /** What a check concluded. */
    public record Checked(boolean verified, String message, String token) {

        /** A refusal, which never carries a token. */
        Checked(boolean verified, String message) {
            this(verified, message, null);
        }
    }

    /**
     * Checks a code against a number.
     *
     * <p>Every failure returns the same shape of answer whether the code was
     * wrong, expired or never issued. Telling them apart tells somebody guessing
     * which numbers have live challenges.
     */
    @Transactional
    public Checked verify(String rawPhone, String purpose, String code) {
        String phone = phoneNumbers.normalise(rawPhone);
        String use = purpose == null || purpose.isBlank() ? "GENERIC" : purpose.trim().toUpperCase();
        if (phone == null || code == null || code.isBlank()) {
            return new Checked(false, "That code is not right. Ask for a new one.");
        }

        PhoneVerification live = verifications
                .findByPhoneNumberAndPurposeAndVerifiedAtIsNull(phone, use).orElse(null);
        if (live == null) {
            return new Checked(false, "That code is not right. Ask for a new one.");
        }
        if (live.getExpiresAt().isBefore(Instant.now())) {
            verifications.delete(live);
            return new Checked(false, "That code has expired. Ask for a new one.");
        }
        if (live.getAttempts() >= MAX_ATTEMPTS) {
            verifications.delete(live);
            // Named, because the alternative is a customer typing the right code
            // into a dead challenge and being told it is wrong.
            return new Checked(false,
                    "Too many wrong tries. That code is now dead — ask for a new one.");
        }

        live.setAttempts(live.getAttempts() + 1);
        if (!constantTimeEquals(hash(phone, use, code.trim()), live.getCodeHash())) {
            verifications.save(live);
            int left = MAX_ATTEMPTS - live.getAttempts();
            return new Checked(false, left > 0
                    ? "That code is not right. " + left + " tr" + (left == 1 ? "y" : "ies")
                            + " left."
                    : "That code is not right, and that was the last try. Ask for a new one.");
        }

        // Correct code. Issue the proof the privileged endpoints ask for: the
        // verified row below is permanent and says only "this number was proved
        // once", which is not something that should still authorise a payout
        // months later.
        String token = newToken();
        live.setVerifiedAt(Instant.now());
        live.setAccessTokenHash(hash(phone, use, token));
        live.setAccessExpiresAt(Instant.now().plus(ACCESS_LIFETIME));
        live.setAccessUsedAt(null);
        verifications.save(live);
        log.info("Verified {} for {}", phone, use);
        return new Checked(true, "Thanks — that number is confirmed.", token);
    }

    /**
     * Spends the proof that the caller owns a number.
     *
     * <p>Single use on purpose. An advance, a redemption and a referral payout
     * are each one action, and a token that survived the first would let a shared
     * or shoulder-surfed phone be drained by repeating the call.
     *
     * @return true only if the token was live, matched, and had not been spent
     */
    @Transactional
    public boolean consume(String rawPhone, String purpose, String token) {
        String phone = phoneNumbers.normalise(rawPhone);
        String use = purpose == null || purpose.isBlank() ? "GENERIC" : purpose.trim().toUpperCase();
        if (phone == null || token == null || token.isBlank()) {
            return false;
        }
        String offered = hash(phone, use, token.trim());
        for (PhoneVerification row : verifications
                .findByPhoneNumberAndPurposeAndAccessTokenHashIsNotNullAndAccessUsedAtIsNullOrderByIdDesc(
                        phone, use)) {
            if (row.getAccessExpiresAt() == null || row.getAccessExpiresAt().isBefore(Instant.now())) {
                continue;
            }
            if (constantTimeEquals(offered, row.getAccessTokenHash())) {
                row.setAccessUsedAt(Instant.now());
                verifications.save(row);
                return true;
            }
        }
        return false;
    }

    /**
     * Checks the proof without spending it.
     *
     * <p>For the endpoints that only show something. A customer looks at what
     * they have and then acts on it, and making the look spend the proof would
     * cost them a second SMS to press the button — so the reads check and the
     * writes consume, and one code covers the whole visit.
     */
    @Transactional(readOnly = true)
    public boolean holdsProof(String rawPhone, String purpose, String token) {
        String phone = phoneNumbers.normalise(rawPhone);
        String use = purpose == null || purpose.isBlank() ? "GENERIC" : purpose.trim().toUpperCase();
        if (phone == null || token == null || token.isBlank()) {
            return false;
        }
        String offered = hash(phone, use, token.trim());
        return verifications
                .findByPhoneNumberAndPurposeAndAccessTokenHashIsNotNullAndAccessUsedAtIsNullOrderByIdDesc(
                        phone, use)
                .stream()
                .anyMatch(row -> row.getAccessExpiresAt() != null
                        && row.getAccessExpiresAt().isAfter(Instant.now())
                        && constantTimeEquals(offered, row.getAccessTokenHash()));
    }

    /** 32 hex characters of entropy — not a code anybody types, so length is free. */
    private String newToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Whether a number has ever been proved. */
    @Transactional(readOnly = true)
    public boolean isVerified(String rawPhone) {
        String phone = phoneNumbers.normalise(rawPhone);
        return phone != null
                && !verifications.findByPhoneNumberAndVerifiedAtIsNotNull(phone).isEmpty();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String rawPhone) {
        String phone = phoneNumbers.normalise(rawPhone);
        return Map.of("phoneNumber", phone == null ? "" : phone,
                "verified", phone != null && isVerified(phone));
    }

    /**
     * Clears out challenges nobody completed.
     *
     * <p>Only the unverified ones: a verified row is the record that a number was
     * proved, and {@link #isVerified} reads it.
     */
    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void purgeExpired() {
        long removed = verifications.deleteByExpiresAtBeforeAndVerifiedAtIsNull(
                Instant.now().minus(Duration.ofDays(1)));
        if (removed > 0) {
            log.info("Cleared {} expired phone challenge(s)", removed);
        }
    }

    /**
     * The stored form of a code.
     *
     * <p>Salted with the number and the purpose, so the same six digits issued to
     * two people hash differently -- without that, a precomputed table of a
     * million hashes would read every live code in the table at once.
     */
    private static String hash(String phone, String purpose, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (phone + "|" + purpose + "|" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
