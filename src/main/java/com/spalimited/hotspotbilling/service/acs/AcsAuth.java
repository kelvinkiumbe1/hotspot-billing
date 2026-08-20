package com.spalimited.hotspotbilling.service.acs;

import com.spalimited.hotspotbilling.domain.AcsSettings;
import com.spalimited.hotspotbilling.repository.AcsSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Who is allowed to talk to the ACS.
 *
 * <p>Before this, nobody had to be anybody: a forged Inform filed a device, and
 * asking for orders handed over whatever was queued for that serial and marked
 * it sent, so the real box never saw it. A serial number is printed on the case
 * and often runs in sequence, which is not a secret.
 *
 * <p>Shut by default. An operator who has not set a password gets an ACS that
 * refuses everything rather than one that quietly accepts everyone — the failure
 * a device cannot check in is loud, and the other kind is silent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcsAuth {

    private final AcsSettingsRepository repository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AcsSettings settings() {
        return repository.findById(AcsSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(AcsSettings.builder()
                        .id(AcsSettings.SINGLETON_ID)
                        .allowUnknown(true)
                        .updatedAt(Instant.now())
                        .build()));
    }

    /** Whether any credentials have been set at all. */
    @Transactional(readOnly = true)
    public boolean configured() {
        AcsSettings s = settings();
        return s.getUsername() != null && !s.getUsername().isBlank()
                && s.getPasswordHash() != null && !s.getPasswordHash().isBlank();
    }

    @Transactional
    public AcsSettings save(String username, String rawPassword, boolean allowUnknown, String by) {
        AcsSettings s = settings();
        if (username != null && !username.isBlank()) {
            s.setUsername(username.trim());
        }
        // Blank means "leave it alone", so re-saving the page does not wipe the
        // password of every device in the field.
        if (rawPassword != null && !rawPassword.isBlank()) {
            if (rawPassword.trim().length() < 12) {
                throw new IllegalArgumentException(
                        "An ACS password needs at least 12 characters — every device shares it");
            }
            s.setPasswordHash(passwordEncoder.encode(rawPassword.trim()));
        }
        s.setAllowUnknown(allowUnknown);
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(by);
        return repository.save(s);
    }

    /**
     * Checks the Basic credentials on a CWMP request.
     *
     * @param header the raw Authorization header, or null
     * @return true only when credentials are configured and match
     */
    @Transactional(readOnly = true)
    public boolean permits(String header) {
        AcsSettings s = settings();
        if (s.getUsername() == null || s.getUsername().isBlank()
                || s.getPasswordHash() == null || s.getPasswordHash().isBlank()) {
            log.warn("An ACS request was refused because no ACS credentials are set");
            return false;
        }
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String user = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        // The username is compared first but the password is still checked either
        // way, so a wrong username does not answer faster than a wrong password.
        boolean userOk = s.getUsername().equals(user);
        boolean passwordOk = passwordEncoder.matches(password, s.getPasswordHash());
        return userOk && passwordOk;
    }
}
