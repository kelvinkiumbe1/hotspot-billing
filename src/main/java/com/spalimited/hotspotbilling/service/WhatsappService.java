package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sends WhatsApp text messages through the Meta Cloud API. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsappService {

    private final MessagingSettingsService messagingSettings;

    public boolean isEnabled() {
        var cfg = messagingSettings.whatsapp();
        return cfg.enabled()
                && cfg.phoneNumberId() != null && !cfg.phoneNumberId().isBlank()
                && cfg.accessToken() != null && !cfg.accessToken().isBlank();
    }

    /**
     * One client per base URL, kept for the life of the application.
     *
     * <p>A client was previously built for every message, so each one paid to
     * open a connection and negotiate TLS with Meta from scratch — measured at
     * a quarter to nine tenths of a second before a single byte of the message
     * moved, on a send that took under two in total. Holding the client lets
     * the connection be reused, and there is only ever one host.
     */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    private RestClient clientFor(String baseUrl) {
        return clients.computeIfAbsent(baseUrl, RestClient::create);
    }

    /** Returns true when the message was accepted by WhatsApp. */
    public boolean send(String phoneNumber, String message) {
        if (!isEnabled()) {
            return false;
        }
        var cfg = messagingSettings.whatsapp();
        try {
            RestClient client = clientFor(cfg.baseUrl());
            client.post()
                    .uri("/" + cfg.phoneNumberId() + "/messages")
                    .header("Authorization", "Bearer " + cfg.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "messaging_product", "whatsapp",
                            "to", phoneNumber,
                            "type", "text",
                            "text", Map.of("body", message)))
                    .retrieve()
                    .toBodilessEntity();
            log.info("WhatsApp message sent to {}", phoneNumber);
            return true;
        } catch (Exception e) {
            log.warn("WhatsApp send to {} failed: {}", phoneNumber, e.getMessage());
            return false;
        }
    }
}
