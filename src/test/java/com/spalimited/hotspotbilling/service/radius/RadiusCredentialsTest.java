package com.spalimited.hotspotbilling.service.radius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Getting a password out of an Access-Request.
 *
 * <p>The PAP tests encode the password exactly as a NAS does and then ask the
 * code to undo it, so the two halves of the algorithm are never checked against
 * each other's assumptions — only against the RFC's.
 */
class RadiusCredentialsTest {

    private static final String SECRET = "testing123";

    /** Encodes a PAP password the way RFC 2865 §5.2 says a NAS must. */
    private static byte[] encodePap(String password, byte[] requestAuthenticator, String secret) {
        byte[] plain = password.getBytes(StandardCharsets.UTF_8);
        int padded = ((plain.length + 15) / 16) * 16;
        if (padded == 0) {
            padded = 16;
        }
        byte[] buffer = new byte[padded];
        System.arraycopy(plain, 0, buffer, 0, plain.length);

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[padded];
        byte[] previous = requestAuthenticator;
        for (int block = 0; block < padded; block += 16) {
            byte[] toHash = new byte[secretBytes.length + 16];
            System.arraycopy(secretBytes, 0, toHash, 0, secretBytes.length);
            System.arraycopy(previous, 0, toHash, secretBytes.length, 16);
            byte[] pad = RadiusPacket.md5(toHash);
            for (int i = 0; i < 16; i++) {
                out[block + i] = (byte) (buffer[block + i] ^ pad[i]);
            }
            previous = java.util.Arrays.copyOfRange(out, block, block + 16);
        }
        return out;
    }

    private static byte[] authenticator() {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (i * 11 + 5);
        }
        return out;
    }

    @Test
    @DisplayName("A short PAP password comes back exactly as typed")
    void shortPassword() {
        byte[] auth = authenticator();
        byte[] encoded = encodePap("ABC123", auth, SECRET);

        assertThat(RadiusCredentials.decodePap(encoded, auth, SECRET)).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("A password longer than one block is chained correctly across blocks")
    void multiBlockPassword() {
        // Over 16 characters, so the second block's pad depends on the first
        // block's ciphertext. Getting that chaining backwards still decodes the
        // first sixteen characters correctly, which is exactly how the bug hides.
        String password = "a-rather-long-pppoe-password-9876";
        byte[] auth = authenticator();
        byte[] encoded = encodePap(password, auth, SECRET);

        assertThat(RadiusCredentials.decodePap(encoded, auth, SECRET)).isEqualTo(password);
    }

    @Test
    @DisplayName("Padding is stripped without truncating a password that is exactly a block")
    void exactBlockLength() {
        String password = "0123456789abcdef"; // exactly 16
        byte[] auth = authenticator();

        assertThat(RadiusCredentials.decodePap(encodePap(password, auth, SECRET), auth, SECRET))
                .isEqualTo(password);
    }

    @Test
    @DisplayName("The wrong shared secret yields nonsense, not an error")
    void wrongSecretIsIndistinguishable() {
        byte[] auth = authenticator();
        byte[] encoded = encodePap("ABC123", auth, SECRET);

        String decoded = RadiusCredentials.decodePap(encoded, auth, "not-the-secret");

        // This is the whole reason a mistyped secret shows up as every login
        // failing rather than as a configuration error: there is nothing in the
        // packet to tell them apart.
        assertThat(decoded).isNotEqualTo("ABC123");
    }

    @Test
    @DisplayName("A truncated or oversized password field is refused rather than parsed")
    void malformedLength() {
        byte[] auth = authenticator();
        assertThat(RadiusCredentials.decodePap(new byte[7], auth, SECRET)).isNull();
        assertThat(RadiusCredentials.decodePap(new byte[0], auth, SECRET)).isNull();
        assertThat(RadiusCredentials.decodePap(new byte[144], auth, SECRET)).isNull();
    }

    @Test
    @DisplayName("A CHAP response matches only the password it was made from")
    void chap() {
        byte[] challenge = authenticator();
        byte identifier = 0x2A;
        String password = "ABC123";

        byte[] chapPassword = new byte[17];
        chapPassword[0] = identifier;
        byte[] toHash = new byte[1 + password.length() + challenge.length];
        toHash[0] = identifier;
        System.arraycopy(password.getBytes(StandardCharsets.UTF_8), 0, toHash, 1, password.length());
        System.arraycopy(challenge, 0, toHash, 1 + password.length(), challenge.length);
        System.arraycopy(RadiusPacket.md5(toHash), 0, chapPassword, 1, 16);

        assertThat(RadiusCredentials.chapMatches(chapPassword, challenge, "ABC123")).isTrue();
        assertThat(RadiusCredentials.chapMatches(chapPassword, challenge, "ABC124")).isFalse();
        // A different challenge is a different hash, which is the entire point
        // of CHAP: a captured response cannot be replayed.
        assertThat(RadiusCredentials.chapMatches(chapPassword, new byte[16], "ABC123")).isFalse();
    }

    @Test
    @DisplayName("A malformed CHAP field never matches anything")
    void chapMalformed() {
        assertThat(RadiusCredentials.chapMatches(null, authenticator(), "x")).isFalse();
        assertThat(RadiusCredentials.chapMatches(new byte[16], authenticator(), "x")).isFalse();
        assertThat(RadiusCredentials.chapMatches(new byte[17], authenticator(), null)).isFalse();
    }

    @Test
    @DisplayName("A rate string becomes the two numbers other vendors want")
    void rateParsing() {
        assertThat(RadiusAuthService.parseRate("5M/10M")).containsExactly(5_000_000L, 10_000_000L);
        assertThat(RadiusAuthService.parseRate("512k/2M")).containsExactly(512_000L, 2_000_000L);
        assertThat(RadiusAuthService.parseRate("1500000/3000000"))
                .containsExactly(1_500_000L, 3_000_000L);
        assertThat(RadiusAuthService.parseRate("2.5M/5M")).containsExactly(2_500_000L, 5_000_000L);
    }

    @Test
    @DisplayName("A rate we cannot read means no speed cap, not a broken login")
    void rateParsingFailsSoftly() {
        assertThat(RadiusAuthService.parseRate("fast")).isNull();
        assertThat(RadiusAuthService.parseRate("5M")).isNull();
        assertThat(RadiusAuthService.parseRate("5M/")).isNull();
        assertThat(RadiusAuthService.parseRate("0/10M")).isNull();
    }
}
