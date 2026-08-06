package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.WhatsappProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Sends WhatsApp text messages through the Meta Cloud API. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsappService {

    private final WhatsappProperties props;

    public boolean isEnabled() {
        return props.enabled()
                && props.phoneNumberId() != null && !props.phoneNumberId().isBlank()
                && props.accessToken() != null && !props.accessToken().isBlank();
    }

    /** Returns true when the message was accepted by WhatsApp. */
    public boolean send(String phoneNumber, String message) {
        if (!isEnabled()) {
            return false;
        }
        try {
            RestClient client = RestClient.create(props.baseUrl());
            client.post()
                    .uri("/" + props.phoneNumberId() + "/messages")
                    .header("Authorization", "Bearer " + props.accessToken())
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
