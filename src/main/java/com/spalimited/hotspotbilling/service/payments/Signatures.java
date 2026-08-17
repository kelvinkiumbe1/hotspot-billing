package com.spalimited.hotspotbilling.service.payments;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhook signature checks, in one place.
 *
 * <p>All three card rails sign their webhooks, each slightly differently, and
 * each check is the only thing standing between a stranger and a free voucher:
 * post a forged "payment succeeded" at an unverified endpoint and the system
 * mints one. So the mechanics live together rather than being written three
 * times, three ways, with two of them subtly wrong.
 */
public final class Signatures {

    private Signatures() {
    }

    /** Lower-case hex HMAC of the body. */
    public static String hmacHex(String algorithm, String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            byte[] digest = mac.doFinal(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // Unable to verify means unable to trust. Never fall through to allow.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not compute a webhook signature");
        }
    }

    /**
     * Compares two signatures without leaking where they first differ.
     *
     * <p>A comparison that returns early gives an attacker a timing oracle:
     * guess a byte, measure, keep the guess that took longer, and the signature
     * falls a byte at a time.
     */
    public static boolean matches(String expected, String given) {
        if (expected == null || given == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().toLowerCase().getBytes(StandardCharsets.UTF_8),
                given.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** Rejects the request; the caller has already decided it is not authentic. */
    public static ResponseStatusException reject(String provider, String why) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                provider + " webhook rejected: " + why);
    }

    /**
     * A header, whatever case the sender used. HTTP header names are
     * case-insensitive and these providers are not consistent about it —
     * Stripe sends Stripe-Signature, Paystack x-paystack-signature.
     */
    public static String header(java.util.Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
