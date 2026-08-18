package com.spalimited.hotspotbilling.service.radius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which sources are allowed to ask this server anything.
 *
 * <p>RADIUS has no certificates and no handshake, so this list is the security
 * boundary rather than a convenience. A match that is too generous lets a
 * stranger drain a customer's pass by forging accounting; one that is too
 * strict takes a site offline.
 */
class RadiusClientMatchTest {

    @Test
    @DisplayName("A plain address matches only itself")
    void exactAddress() {
        assertThat(RadiusServer.matches("10.0.0.1", "10.0.0.1")).isTrue();
        assertThat(RadiusServer.matches("10.0.0.1", "10.0.0.2")).isFalse();
        // Not a prefix match: 10.0.0.1 must not admit 10.0.0.10.
        assertThat(RadiusServer.matches("10.0.0.1", "10.0.0.10")).isFalse();
    }

    @Test
    @DisplayName("A CIDR block admits the addresses inside it and no others")
    void cidrBlock() {
        assertThat(RadiusServer.matches("10.90.0.0/24", "10.90.0.7")).isTrue();
        assertThat(RadiusServer.matches("10.90.0.0/24", "10.90.0.255")).isTrue();
        assertThat(RadiusServer.matches("10.90.0.0/24", "10.90.1.1")).isFalse();
        assertThat(RadiusServer.matches("192.168.88.0/22", "192.168.90.5")).isTrue();
        assertThat(RadiusServer.matches("192.168.88.0/22", "192.168.92.5")).isFalse();
    }

    @Test
    @DisplayName("A /32 is a single address and a /0 is everything")
    void edgePrefixes() {
        assertThat(RadiusServer.matches("10.0.0.5/32", "10.0.0.5")).isTrue();
        assertThat(RadiusServer.matches("10.0.0.5/32", "10.0.0.6")).isFalse();
        // Java shifts by (32 - 0) as a shift by zero, which would silently turn
        // "everything" into "nothing" — the whole network offline at once.
        assertThat(RadiusServer.matches("0.0.0.0/0", "203.0.113.9")).isTrue();
    }

    @Test
    @DisplayName("Nonsense never matches, rather than matching everything")
    void malformedIsClosed() {
        assertThat(RadiusServer.matches("not-an-address", "10.0.0.1")).isFalse();
        assertThat(RadiusServer.matches("10.0.0.0/99", "10.0.0.1")).isFalse();
        assertThat(RadiusServer.matches("10.0.0.0/24", "10.0.0.999")).isFalse();
        assertThat(RadiusServer.matches("10.0.0.0/-1", "10.0.0.1")).isFalse();
        assertThat(RadiusServer.matches(null, "10.0.0.1")).isFalse();
        assertThat(RadiusServer.matches("10.0.0.1", null)).isFalse();
        // An IPv6 source against an IPv4 rule: no match, not an exception.
        assertThat(RadiusServer.matches("10.0.0.0/24", "fe80::1")).isFalse();
    }

    @Test
    @DisplayName("Surrounding whitespace does not take a site offline")
    void tolerantOfSpacing() {
        assertThat(RadiusServer.matches("  10.0.0.1  ", "10.0.0.1")).isTrue();
        assertThat(RadiusServer.matches(" 10.90.0.0/24 ", "10.90.0.7")).isTrue();
    }
}
