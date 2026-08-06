package com.spalimited.hotspotbilling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMS gateway settings (Africa's Talking). Disabled by default so the app
 * runs without an SMS account during development.
 */
@ConfigurationProperties(prefix = "sms")
public record SmsProperties(
        boolean enabled,
        String username,
        String apiKey,
        String senderId,
        String baseUrl
) {
}
