package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.AuthTokenRepository;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records failed sign-ins.
 *
 * <p>A separate bean on purpose. A rejected sign-in ends in an exception,
 * which rolls back the transaction it was thrown from — so counting the
 * failure in the same transaction quietly undoes it, and the account never
 * locks however many times it is guessed. This runs in its own transaction
 * that commits regardless of what happens to the caller's.
 *
 * <p>It has to be a distinct bean rather than a method on AuthService,
 * because Spring's transaction handling works through a proxy and a class
 * calling its own method never passes through it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

    private final StaffUserRepository staff;
    private final AuthTokenRepository tokens;

    /** Returns how many attempts remain; zero means the account just locked. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long staffUserId, int maxAttempts) {
        StaffUser user = staff.findById(staffUserId).orElse(null);
        if (user == null) {
            return maxAttempts;
        }
        user.setFailedAttempts(user.getFailedAttempts() + 1);
        user.setLastFailedAt(Instant.now());

        if (user.getFailedAttempts() >= maxAttempts && user.getLockedAt() == null) {
            user.setLockedAt(Instant.now());
            // Anything already signed in is no longer trustworthy.
            tokens.deleteForUser(user.getId());
            log.warn("Locked {} after {} failed sign-ins", user.getUsername(), user.getFailedAttempts());
        }
        staff.save(user);
        return Math.max(0, maxAttempts - user.getFailedAttempts());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long staffUserId) {
        staff.findById(staffUserId).ifPresent(user -> {
            user.setFailedAttempts(0);
            user.setLastFailedAt(null);
            user.setLastLoginAt(Instant.now());
            staff.save(user);
        });
    }
}
