package com.spalimited.hotspotbilling.config;

import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Proves an inbound WhatsApp webhook really came from Meta.
 *
 * <p>The bots behind that endpoint trust one thing — the {@code from} number
 * in the payload. The customer bot will hand that number its voucher code and
 * account state; the field bot will hand it the entire open job queue, every
 * customer's name and phone number on it, and the ability to send messages
 * that arrive as the business. All of which is fine, and only fine, if the
 * number is not simply asserted by whoever found the URL.
 *
 * <p>Meta signs every delivery as {@code X-Hub-Signature-256: sha256=<hex>},
 * an HMAC-SHA256 of the exact request body under the app secret. So the body
 * must be hashed as received — parsing and re-serialising it would change the
 * bytes and every signature would fail.
 *
 * <p>With no app secret configured this warns loudly and lets the request
 * through, matching {@link MpesaCallbackGuard}: an operator halfway through
 * setup should not be met with a silent rejection they cannot diagnose. That
 * open state is not left to a log line, though — the health monitor raises it
 * as an alert for as long as it lasts.
 */
@Component
@RequiredArgsConstructor
public class WhatsappSignatureGuard {

    private static final Logger log = LoggerFactory.getLogger(WhatsappSignatureGuard.class);
    private static final String PREFIX = "sha256=";

    private final MessagingSettingsService messagingSettings;

    /** True when a secret is configured and signatures are therefore enforced. */
    public boolean isEnforcing() {
        return messagingSettings.settings().isInboundVerifiable();
    }

    /**
     * Rejects the request unless the header matches an HMAC of the body.
     *
     * @param rawBody the bytes exactly as received, not a re-serialised copy
     * @param header  the value of X-Hub-Signature-256, or null
     */
    public void assertFromMeta(byte[] rawBody, String header) {
        String secret = messagingSettings.settings().getWhatsappAppSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("WhatsApp webhook signatures are not being checked — no Meta app secret is set. "
                    + "Anyone who knows this URL can impersonate any customer or technician. "
                    + "Add it under Settings → SMS & WhatsApp before going live.");
            return;
        }
        if (header == null || !header.startsWith(PREFIX)) {
            reject("no X-Hub-Signature-256 header");
        }
        String expected = hmacHex(secret, rawBody == null ? new byte[0] : rawBody);
        String given = header.substring(PREFIX.length()).trim();
        // Constant-time: a length-or-content comparison that returns early
        // leaks, one byte at a time, what the right answer would have been.
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8))) {
            reject("signature did not match");
        }
    }

    private static void reject(String why) {
        log.warn("Rejected a WhatsApp webhook: {}", why);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an authorised webhook source");
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // Cannot verify means cannot trust. Never fall through to "allow".
            log.error("Could not compute the WhatsApp webhook signature: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Signature check failed");
        }
    }
}
