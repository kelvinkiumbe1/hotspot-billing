package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.AiSettings;
import com.spalimited.hotspotbilling.repository.AiSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiSettingsService {

    private final AiSettingsRepository repo;

    @Transactional
    public AiSettings get() {
        return repo.findById(AiSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(AiSettings.builder().id(AiSettings.SINGLETON_ID).build()));
    }

    @Transactional
    public Map<String, Object> describe() {
        AiSettings s = get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.isEnabled());
        out.put("model", s.getModel());
        out.put("apiKey", mask(s.getApiKey()));
        out.put("draftTicketReplies", s.isDraftTicketReplies());
        out.put("working", s.isConfigured());
        return out;
    }

    @Transactional
    public AiSettings save(AiSettings in) {
        AiSettings s = get();
        s.setEnabled(in.isEnabled());
        s.setModel(in.getModel() == null || in.getModel().isBlank()
                ? AiSettings.DEFAULT_MODEL : in.getModel().trim());
        s.setDraftTicketReplies(in.isDraftTicketReplies());
        // Blank or still-masked key means "keep the stored one".
        if (in.getApiKey() != null && !in.getApiKey().isBlank() && !in.getApiKey().startsWith("••••")) {
            s.setApiKey(in.getApiKey().trim());
        }
        return repo.save(s);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String tail = secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
        return "••••••••" + tail;
    }
}
