package com.spalimited.hotspotbilling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WhatsApp Cloud API settings. When enabled, notifications go out on
 * WhatsApp first and fall back to SMS if the send fails.
 */
@ConfigurationProperties(prefix = "whatsapp")
public record WhatsappProperties(
        boolean enabled,
        String phoneNumberId,
        String accessToken,
        String baseUrl
) {
}
