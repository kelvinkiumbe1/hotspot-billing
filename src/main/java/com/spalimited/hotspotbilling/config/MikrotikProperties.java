package com.spalimited.hotspotbilling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MikroTik RouterOS API connection settings. Disabled by default so the
 * app runs without a router during development.
 */
@ConfigurationProperties(prefix = "mikrotik")
public record MikrotikProperties(
        boolean enabled,
        String host,
        int port,
        String username,
        String password
) {
}
