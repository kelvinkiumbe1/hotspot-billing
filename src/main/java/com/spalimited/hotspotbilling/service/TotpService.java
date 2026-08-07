package com.spalimited.hotspotbilling.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords, RFC 6238. Compatible with Google
 * Authenticator, Authy, 1Password and the rest — they all implement the
 * same standard, so there is no vendor to sign up with and nothing to pay.
 *
 * <p>Written out rather than pulled from a library because it is about
 * eighty lines of well-specified arithmetic, and a dependency that touches
 * authentication is a dependency worth avoiding.
 */
@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;

    /**
     * How many 30-second steps either side of now are accepted. One step
     * covers a phone clock that drifts slightly and the seconds a person
     * spends typing; more than that widens the window an attacker has.
     */
    private static final int TOLERANCE_STEPS = 1;

    private final SecureRandom random = new SecureRandom();

    /** A fresh 160-bit secret, Base32 encoded as the apps expect. */
    public String newSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * The otpauth:// URI an authenticator app scans. The issuer appears
     * twice by convention: as a label prefix for older apps and as a
     * parameter for newer ones.
     */
    public String provisioningUri(String secret, String username, String issuer) {
        String label = enc(issuer) + ":" + enc(username);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** True when the code matches, allowing for a little clock drift. */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        String cleaned = code.replaceAll("\\s", "");
        if (!cleaned.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        long step = Instant.now().getEpochSecond() / PERIOD_SECONDS;
        for (int offset = -TOLERANCE_STEPS; offset <= TOLERANCE_STEPS; offset++) {
            if (constantTimeEquals(generate(secret, step + offset), cleaned)) {
                return true;
            }
        }
        return false;
    }

    /** The code for a given time step. Exposed for tests. */
    public String generate(String secret, long step) {
        byte[] key = base32Decode(secret);
        byte[] counter = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a one-time code", e);
        }
    }

    /**
     * Compares without leaking, through timing, how much of the code was
     * right — otherwise a code can be guessed digit by digit.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String clean = encoded.trim().replace("=", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int index = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("That is not a valid secret");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
