package com.spalimited.hotspotbilling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Safaricom Daraja API credentials. Set via application.yml or environment
 * variables (MPESA_CONSUMER_KEY etc.). Defaults point at the sandbox.
 */
@ConfigurationProperties(prefix = "mpesa")
public record MpesaProperties(
        String baseUrl,
        String consumerKey,
        String consumerSecret,
        String shortCode,
        String passkey,
        String callbackUrl,
        /** PayBill number customers deposit to (C2B); blank if unused. */
        String paybill
) {
}
