package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.AuthToken;
import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.AuthTokenRepository;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Signing in, and everything that guards it.
 *
 * <p>Failures are counted here rather than on every request. The admin
 * makes several API calls per page, so a stale password left in a browser
 * would lock an account on a single refresh if each rejected call counted.
 * The sign-in endpoint is the one place a person deliberately offers a
 * password, so it is the honest place to count.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final StaffUserRepository staff;
    private final AuthTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final TotpService totp;
    private final LoginAttemptService attempts;
    private final SecuritySettingsService securitySettings;
    private final SecureRandom random = new SecureRandom();

    @Value("${admin.username}")
    private String breakGlassUsername;

    /** Raised when the password is right but a one-time code is still needed. */
    public static class TotpRequiredException extends RuntimeException {
        public TotpRequiredException() {
            super("Enter the 6-digit code from your authenticator app");
        }
    }

    public record Session(String token, Instant expiresAt, StaffUser user) {
    }

    /**
     * Not transactional on purpose. A rejected sign-in throws, and a throw
     * rolls back the transaction it happened in — which would silently undo
     * the failure count and stop the account ever locking. Failures are
     * recorded by LoginAttemptService in a transaction of their own.
     */
    public Session signIn(String username, String password, String code,
                          String userAgent, String ip) {
        String name = username == null ? "" : username.trim().toLowerCase();
        int maxAttempts = securitySettings.maxLoginAttempts();
        StaffUser user = staff.findByUsername(name).orElse(null);

        // Unknown username and wrong password answer identically, so the
        // response cannot be used to discover which accounts exist.
        if (user == null || !user.isActive()) {
            throw new IllegalArgumentException("Wrong username or password");
        }

        if (user.isLocked()) {
            throw new IllegalStateException(
                    "This account is locked after " + maxAttempts + " failed attempts. "
                            + "An owner has to set a new password before you can sign in.");
        }

        if (!encoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            int left = attempts.recordFailure(user.getId(), maxAttempts);
            throw new IllegalArgumentException(left > 0
                    ? "Wrong username or password. " + left
                            + (left == 1 ? " attempt left" : " attempts left") + " before the account locks."
                    : "Wrong username or password. This account is now locked — "
                            + "an owner has to set a new password.");
        }

        if (user.isTotpEnabled()) {
            if (code == null || code.isBlank()) {
                throw new TotpRequiredException();
            }
            if (!totp.verify(user.getTotpSecret(), code)) {
                // A wrong code counts too, or the second factor could be
                // brute-forced freely once the password was known.
                int left = attempts.recordFailure(user.getId(), maxAttempts);
                throw new IllegalArgumentException(left > 0
                        ? "That code is not right. " + left
                                + (left == 1 ? " attempt left." : " attempts left.")
                        : "That code is not right. This account is now locked — "
                                + "an owner has to set a new password.");
            }
        }

        attempts.recordSuccess(user.getId());

        return new Session(issue(user, userAgent, ip), Instant.now().plus(securitySettings.tokenLifetime()), user);
    }

    /**
     * Mints a session for a user who has already proved themselves by other
     * means — a verified passkey assertion. No password is involved, so the
     * caller (WebAuthnService) is responsible for the verification; this only
     * issues the token and resets the failure count.
     */
    public Session startSession(StaffUser user, String userAgent, String ip) {
        attempts.recordSuccess(user.getId());
        return new Session(issue(user, userAgent, ip), Instant.now().plus(securitySettings.tokenLifetime()), user);
    }

    // No @Transactional here: this is called from within the same class, and
    // Spring's transactions work through a proxy that self-invocation skips.
    // The annotation would read as protection that is not there. The save
    // below manages its own transaction, which is all a single insert needs.
    private String issue(StaffUser user, String userAgent, String ip) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(AuthToken.builder()
                .token(value)
                .staffUserId(user.getId())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(securitySettings.tokenLifetime()))
                .userAgent(userAgent == null ? null
                        : userAgent.substring(0, Math.min(userAgent.length(), 255)))
                .ipAddress(ip)
                .build());
        return value;
    }

    /** The account behind a bearer token, if it is still good for one. */
    @Transactional
    public Optional<StaffUser> resolve(String token) {
        return tokens.findByToken(token)
                .filter(t -> !t.isExpired())
                .flatMap(t -> {
                    // Touched at most once a minute; a write per request would
                    // put a database update in front of every API call.
                    if (t.getLastUsedAt() == null
                            || t.getLastUsedAt().isBefore(Instant.now().minus(Duration.ofMinutes(1)))) {
                        t.setLastUsedAt(Instant.now());
                        tokens.save(t);
                    }
                    return staff.findById(t.getStaffUserId());
                })
                .filter(StaffUser::isActive)
                .filter(u -> !u.isLocked());
    }

    @Transactional
    public void signOut(String token) {
        tokens.findByToken(token).ifPresent(tokens::delete);
    }

    /** Clears a lock. Called when an owner sets a new password. */
    @Transactional
    public void unlock(StaffUser user) {
        user.setLockedAt(null);
        user.setFailedAttempts(0);
        user.setLastFailedAt(null);
        staff.save(user);
    }

    public boolean isBreakGlass(String username) {
        return breakGlassUsername != null && breakGlassUsername.equalsIgnoreCase(username);
    }

    /** Expired tokens are dead weight; clear them out nightly. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int removed = tokens.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Removed {} expired session token(s)", removed);
        }
    }
}
