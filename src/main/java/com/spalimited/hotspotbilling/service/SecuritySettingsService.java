package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.SecuritySettings;
import com.spalimited.hotspotbilling.repository.SecuritySettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * The single security-policy row, seeded on first use from the previous
 * env-based defaults so nothing changes for existing deployments until an
 * owner edits it. AuthService and WebAuthnService read their limits here
 * rather than from constants, so the Settings page can change them live.
 */
@Service
@RequiredArgsConstructor
public class SecuritySettingsService {

    private final SecuritySettingsRepository repo;

    /** Seed for require-passkeys, taken from the old env flag on first boot. */
    @Value("${webauthn.enrollment-required:false}")
    private boolean seedRequirePasskeys;

    @Transactional
    public SecuritySettings get() {
        return repo.findById(SecuritySettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(SecuritySettings.builder()
                        .id(SecuritySettings.SINGLETON_ID)
                        .requirePasskeys(seedRequirePasskeys)
                        .sessionTimeoutHours(12)
                        .maxLoginAttempts(5)
                        .build()));
    }

    @Transactional
    public SecuritySettings update(SecuritySettings in) {
        SecuritySettings s = get();
        s.setRequirePasskeys(in.isRequirePasskeys());
        // Clamp to sane bounds so a typo can't lock everyone out or leave a
        // session valid forever.
        s.setSessionTimeoutHours(Math.max(1, Math.min(720, in.getSessionTimeoutHours())));
        s.setMaxLoginAttempts(Math.max(3, Math.min(20, in.getMaxLoginAttempts())));
        return repo.save(s);
    }

    @Transactional(readOnly = true)
    public boolean requirePasskeys() {
        return get().isRequirePasskeys();
    }

    @Transactional(readOnly = true)
    public int maxLoginAttempts() {
        return get().getMaxLoginAttempts();
    }

    @Transactional(readOnly = true)
    public Duration tokenLifetime() {
        return Duration.ofHours(get().getSessionTimeoutHours());
    }
}
