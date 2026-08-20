package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Proves that whoever is calling owns the phone number in the path.
 *
 * <p>The endpoints behind this take a phone number and nothing else: a customer
 * on the captive portal has no login to offer. That was fine while they only
 * showed a balance, and became a hole the moment they started giving things
 * away — anybody could post a stranger's number and take a pass on credit in
 * their name.
 *
 * <p>So the number is proved the same way a bank proves one: a code goes to the
 * phone by SMS, and entering it hands back a short-lived single-use token. The
 * token is what these endpoints require. Somebody who does not hold the handset
 * never sees the code, so they never get the token.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PhoneOwnership {

    private final PhoneVerificationService verification;

    /** Thrown when the caller cannot prove the number is theirs. Maps to 403. */
    public static class NotProved extends RuntimeException {
        public NotProved(String message) {
            super(message);
        }
    }

    /**
     * Checks the proof without spending it, for endpoints that only show
     * something. Same refusal, so a caller learns nothing from which one ran.
     */
    public void check(String phone, String purpose, String token) {
        if (token == null || token.isBlank()) {
            throw new NotProved("Confirm your number first: ask for a code at "
                    + "/api/verify/request with purpose " + purpose + ".");
        }
        if (!verification.holdsProof(phone, purpose, token)) {
            log.warn("Rejected an unproved {} read for {}", purpose, phone);
            throw new NotProved("That confirmation is not valid any more. "
                    + "Ask for a new code and try again.");
        }
    }

    /**
     * Spends the caller's proof, or refuses.
     *
     * <p>The message says which purpose to ask for a code with, because the
     * alternative is a portal that fails with nothing a customer can act on.
     *
     * @throws NotProved if the token is missing, wrong, expired or already spent
     */
    public void require(String phone, String purpose, String token) {
        if (token == null || token.isBlank()) {
            throw new NotProved("Confirm your number first: ask for a code at "
                    + "/api/verify/request with purpose " + purpose + ".");
        }
        if (!verification.consume(phone, purpose, token)) {
            // Deliberately one message for every failure. Telling a caller that a
            // token was expired rather than wrong tells them the number has been
            // proved before, which is exactly what they are fishing for.
            log.warn("Rejected an unproved {} request for {}", purpose, phone);
            throw new NotProved("That confirmation is not valid any more. "
                    + "Ask for a new code and try again.");
        }
    }
}
