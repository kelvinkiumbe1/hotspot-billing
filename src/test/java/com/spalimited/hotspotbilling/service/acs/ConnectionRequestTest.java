package com.spalimited.hotspotbilling.service.acs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digest handshake that pokes a router into calling home.
 *
 * <p>Worth testing without a router because getting it wrong looks exactly like
 * wrong credentials: the CPE answers 401 again and an operator concludes the
 * password is bad when the header was malformed.
 */
class ConnectionRequestTest {

    @Test
    @DisplayName("A challenge is taken apart into its fields")
    void challengeIsParsed() {
        Map<String, String> fields = ConnectionRequest.parseChallenge(
                "Digest realm=\"HuaweiHomeGateway\", nonce=\"abc123\", qop=\"auth\"");

        assertThat(fields).containsEntry("realm", "HuaweiHomeGateway");
        assertThat(fields).containsEntry("nonce", "abc123");
        assertThat(fields).containsEntry("qop", "auth");
    }

    @Test
    @DisplayName("A comma inside a realm does not split the challenge")
    void commasInsideQuotesSurvive() {
        // Splitting naively loses every field after the realm, and what is lost
        // is the nonce -- so the response is computed against nothing and the
        // device rejects it, looking exactly like a wrong password.
        Map<String, String> fields = ConnectionRequest.parseChallenge(
                "Digest realm=\"Acme, Inc Gateway\", nonce=\"xyz\"");

        assertThat(fields).containsEntry("realm", "Acme, Inc Gateway");
        assertThat(fields).containsEntry("nonce", "xyz");
    }

    @Test
    @DisplayName("The header carries everything a CPE checks")
    void headerIsComplete() {
        String header = ConnectionRequest.digest(
                "Digest realm=\"Gateway\", nonce=\"n1\", qop=\"auth\", opaque=\"op1\"",
                "http://10.1.2.3:7547/cr", "acs", "secret");

        assertThat(header).startsWith("Digest ");
        assertThat(header).contains("username=\"acs\"");
        assertThat(header).contains("realm=\"Gateway\"");
        assertThat(header).contains("nonce=\"n1\"");
        // The path only, not the whole URL -- a CPE computes HA2 over the path and
        // a full URL here mismatches every time.
        assertThat(header).contains("uri=\"/cr\"");
        assertThat(header).contains("opaque=\"op1\"");
        // qop was offered, so nc and cnonce are required rather than optional.
        assertThat(header).contains("qop=auth");
        assertThat(header).contains("nc=00000001");
        assertThat(header).contains("cnonce=\"");
    }

    @Test
    @DisplayName("Without qop the simpler response is sent, and no cnonce")
    void withoutQopTheOldFormIsUsed() {
        // Older CPEs offer no qop at all. Sending nc and cnonce anyway is a
        // different computation and they reject it.
        String header = ConnectionRequest.digest(
                "Digest realm=\"Gateway\", nonce=\"n1\"",
                "http://10.1.2.3:7547/cr", "acs", "secret");

        assertThat(header).doesNotContain("qop=");
        assertThat(header).doesNotContain("cnonce");
        assertThat(header).contains("response=\"");
    }

    @Test
    @DisplayName("A URL with no path still authenticates against something")
    void aBareUrlGetsASlash() {
        // HA2 is over the path; an empty one produces a hash of "GET:" which no
        // device agrees with.
        String header = ConnectionRequest.digest(
                "Digest realm=\"g\", nonce=\"n\"", "http://10.1.2.3:7547", "acs", "s");

        assertThat(header).contains("uri=\"/\"");
    }

    @Test
    @DisplayName("A device that told us nowhere to call is not called")
    void noUrlIsNotAnError() {
        // A CPE behind carrier-grade NAT is the normal case, not a failure.
        ConnectionRequest.Result result = new ConnectionRequest().poke(
                com.spalimited.hotspotbilling.domain.CpeDevice.builder()
                        .oui("001122").serialNumber("SN").build());

        assertThat(result.reached()).isFalse();
        assertThat(result.detail()).contains("where to reach it");
    }
}
