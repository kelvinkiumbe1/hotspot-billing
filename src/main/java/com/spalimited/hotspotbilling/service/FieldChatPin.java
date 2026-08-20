package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * The PIN that stands between a phone and other people's customers.
 *
 * <p>The field bot used to trust the number a message arrived from and nothing
 * else. That made a technician's handset a standing credential to the whole open
 * job queue — every customer's name, address and phone — plus the power to close
 * jobs and message people as the business. A lost phone, a borrowed one or a
 * recycled SIM was a data breach with no way to notice.
 *
 * <p>The office sets the PIN, never the technician on first contact: whoever is
 * holding the phone would just set it themselves, which is no gate at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldChatPin {

    /** How long one entry of the PIN is good for. Matches the chat session. */
    public static final Duration UNLOCK_FOR = Duration.ofMinutes(30);

    private static final int MAX_FAILURES = 5;

    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    /** Short enough to type one-handed on a ladder, long enough to not be guessed in five. */
    private static final int MIN_LENGTH = 4;

    private static final int MAX_LENGTH = 8;

    private final TechnicianRepository technicians;
    private final PasswordEncoder passwordEncoder;
    private final OperatorAlertService alerts;

    /** What the bot needs to know before it says anything else. */
    public enum State { NO_PIN_SET, LOCKED_OUT, NEEDS_PIN, UNLOCKED }

    public boolean hasPin(Technician tech) {
        return tech.getChatPinHash() != null && !tech.getChatPinHash().isBlank();
    }

    public boolean lockedOut(Technician tech) {
        return tech.getChatPinLockedUntil() != null
                && tech.getChatPinLockedUntil().isAfter(Instant.now());
    }

    /**
     * Sets or replaces a technician's PIN. Office-side.
     *
     * <p>Setting one also clears a lockout, because the reason an office is
     * setting a PIN is usually that somebody is locked out.
     */
    @Transactional
    public void setPin(Long technicianId, String rawPin, String by) {
        Technician tech = technicians.findById(technicianId)
                .orElseThrow(() -> new IllegalArgumentException("No such technician"));
        String pin = rawPin == null ? "" : rawPin.trim();
        if (!pin.matches("\\d{" + MIN_LENGTH + "," + MAX_LENGTH + "}")) {
            throw new IllegalArgumentException(
                    "A field PIN is " + MIN_LENGTH + " to " + MAX_LENGTH + " digits");
        }
        if (pin.chars().distinct().count() == 1) {
            // 0000 and 1111 are the first two guesses anybody makes.
            throw new IllegalArgumentException("That PIN is too easy to guess — vary the digits");
        }
        tech.setChatPinHash(passwordEncoder.encode(pin));
        tech.setChatPinSetAt(Instant.now());
        tech.setChatPinFailures(0);
        tech.setChatPinLockedUntil(null);
        technicians.save(tech);
        log.info("Field chat PIN set for technician {} by {}", tech.getId(), by);
    }

    /** Takes the PIN away, which shuts that technician out of the bot entirely. */
    @Transactional
    public void clearPin(Long technicianId, String by) {
        Technician tech = technicians.findById(technicianId)
                .orElseThrow(() -> new IllegalArgumentException("No such technician"));
        tech.setChatPinHash(null);
        tech.setChatPinSetAt(null);
        tech.setChatPinFailures(0);
        tech.setChatPinLockedUntil(null);
        technicians.save(tech);
        log.info("Field chat PIN cleared for technician {} by {}", tech.getId(), by);
    }

    /**
     * Checks an entered PIN.
     *
     * <p>A wrong one counts, and the count survives the chat session — otherwise
     * saying "menu" between guesses would reset the budget and there would be no
     * limit at all. The operator hears about a lockout, because a technician
     * locked out is either a wrong PIN or somebody else holding the phone, and
     * only the operator can tell which.
     */
    @Transactional
    public boolean accept(Technician tech, String entered) {
        if (!hasPin(tech) || lockedOut(tech)) {
            return false;
        }
        String pin = entered == null ? "" : entered.trim();
        if (passwordEncoder.matches(pin, tech.getChatPinHash())) {
            tech.setChatPinFailures(0);
            technicians.save(tech);
            return true;
        }
        int failures = tech.getChatPinFailures() + 1;
        tech.setChatPinFailures(failures);
        if (failures >= MAX_FAILURES) {
            tech.setChatPinLockedUntil(Instant.now().plus(LOCKOUT));
            tech.setChatPinFailures(0);
            technicians.save(tech);
            log.warn("Field chat locked for technician {} after {} wrong PINs",
                    tech.getId(), MAX_FAILURES);
            alerts.alert(FieldOpsService.firstName(tech.getFullName())
                    + "'s field chat was locked after " + MAX_FAILURES + " wrong PINs. If that "
                    + "was not them, their phone is in somebody else's hands.");
            return false;
        }
        technicians.save(tech);
        return false;
    }

    /** How many guesses are left before a lockout, for the message the bot sends. */
    public int triesLeft(Technician tech) {
        return Math.max(0, MAX_FAILURES - tech.getChatPinFailures());
    }
}
