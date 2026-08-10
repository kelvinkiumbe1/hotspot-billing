package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.EmailSettings;
import com.spalimited.hotspotbilling.repository.EmailSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Effective SMTP configuration, held as a single row. Mirrors
 * {@link MessagingSettingsService}: the password is write-only — it is
 * masked when read back, and a blank/masked value on save means "keep the
 * stored one" rather than wiping it.
 */
@Service
@RequiredArgsConstructor
public class EmailSettingsService {

    private final EmailSettingsRepository repo;

    @Transactional
    public EmailSettings get() {
        return repo.findById(EmailSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(EmailSettings.builder()
                        .id(EmailSettings.SINGLETON_ID)
                        .build()));
    }

    /** Current state with the password masked, for the settings screen. */
    @Transactional
    public Map<String, Object> describe() {
        EmailSettings s = get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.isEnabled());
        out.put("host", s.getHost());
        out.put("port", s.getPort());
        out.put("username", s.getUsername());
        out.put("password", mask(s.getPassword()));
        out.put("fromAddress", s.getFromAddress());
        out.put("fromName", s.getFromName());
        out.put("startTls", s.isStartTls());
        out.put("working", s.isConfigured());
        out.put("updatedBy", s.getUpdatedBy());
        return out;
    }

    @Transactional
    public EmailSettings save(EmailSettings in, String updatedBy) {
        EmailSettings s = get();
        s.setEnabled(in.isEnabled());
        s.setHost(trim(in.getHost()));
        s.setPort(in.getPort() <= 0 ? 587 : Math.min(65535, in.getPort()));
        s.setUsername(trim(in.getUsername()));
        s.setFromAddress(trim(in.getFromAddress()));
        s.setFromName(trim(in.getFromName()));
        s.setStartTls(in.isStartTls());
        // A blank or still-masked secret means "keep what is stored".
        if (notBlank(in.getPassword()) && !in.getPassword().startsWith("••••")) {
            s.setPassword(in.getPassword().trim());
        }
        s.setUpdatedBy(updatedBy);
        return repo.save(s);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String tail = secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
        return "••••••••" + tail;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
