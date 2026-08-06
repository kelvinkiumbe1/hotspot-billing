package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Stamps when each office account last signed in, so the staff page can show
 * it rather than always reading "never".
 *
 * <p>The API is stateless Basic auth, so every single request authenticates.
 * Writing on each one would be a database update per request, so the stamp is
 * only refreshed once it is older than a few minutes — enough for "last seen"
 * without the write traffic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoginRecorder {

    private static final Duration REFRESH_AFTER = Duration.ofMinutes(5);

    private final StaffUserRepository staff;

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        try {
            staff.findByUsername(username).ifPresent(member -> {
                Instant last = member.getLastLoginAt();
                if (last == null || last.isBefore(Instant.now().minus(REFRESH_AFTER))) {
                    member.setLastLoginAt(Instant.now());
                    staff.save(member);
                }
            });
        } catch (Exception e) {
            // Never let bookkeeping break a sign-in.
            log.debug("Could not stamp the last login for {}: {}", username, e.getMessage());
        }
    }
}
