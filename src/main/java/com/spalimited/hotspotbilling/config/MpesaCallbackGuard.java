package com.spalimited.hotspotbilling.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Guards the public M-Pesa callback endpoints so only Safaricom can post to
 * them. Without this, the STK "success" callback is unauthenticated: a
 * customer who has their own CheckoutRequestID (it is handed to their browser)
 * could forge a success and be issued a voucher without paying.
 *
 * <p>The allowlist is a comma-separated list of IPv4 addresses or CIDR ranges.
 * Empty means "accept from anywhere" — convenient for the sandbox, but logged
 * loudly as a warning because it must not be the state in production.
 *
 * <p>Behind the reverse proxy (Caddy) the caller's real address arrives in
 * X-Forwarded-For; this trusts the first hop, which is safe only because the
 * app is not directly exposed — the proxy sets that header. If you ever expose
 * the app port directly, this check can be spoofed and must move to the proxy.
 */
@Component
public class MpesaCallbackGuard {

    private static final Logger log = LoggerFactory.getLogger(MpesaCallbackGuard.class);

    private final List<Cidr> allowed = new ArrayList<>();
    private final boolean enforce;

    public MpesaCallbackGuard(@Value("${mpesa.callback-allowed-ips:}") String csv) {
        for (String entry : csv.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                try {
                    allowed.add(Cidr.parse(trimmed));
                } catch (RuntimeException e) {
                    log.error("Ignoring invalid mpesa.callback-allowed-ips entry '{}': {}", trimmed, e.getMessage());
                }
            }
        }
        this.enforce = !allowed.isEmpty();
        if (!enforce) {
            log.warn("M-Pesa callback IP allowlist is empty — callbacks are accepted from ANY source. "
                    + "Set mpesa.callback-allowed-ips (Safaricom's ranges) before going live.");
        }
    }

    public void assertFromSafaricom(HttpServletRequest request) {
        if (!enforce) {
            return;
        }
        String ip = clientIp(request);
        long value;
        try {
            value = Cidr.toLong(ip);
        } catch (RuntimeException e) {
            // Non-IPv4 (e.g. an IPv6 caller) is never a Safaricom source.
            reject(ip);
            return;
        }
        for (Cidr cidr : allowed) {
            if (cidr.contains(value)) {
                return;
            }
        }
        reject(ip);
    }

    private void reject(String ip) {
        log.warn("Rejected M-Pesa callback from unauthorised source {}", ip);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an authorised callback source");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Minimal IPv4 CIDR — Safaricom's callback sources are all IPv4. */
    record Cidr(long network, long mask) {
        static Cidr parse(String spec) {
            String[] parts = spec.split("/");
            long ip = toLong(parts[0].trim());
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 32;
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("prefix out of range: " + prefix);
            }
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return new Cidr(ip & mask, mask);
        }

        boolean contains(long ip) {
            return (ip & mask) == network;
        }

        static long toLong(String ipv4) {
            String[] octets = ipv4.split("\\.");
            if (octets.length != 4) {
                throw new IllegalArgumentException("not IPv4: " + ipv4);
            }
            long value = 0;
            for (String octet : octets) {
                int o = Integer.parseInt(octet.trim());
                if (o < 0 || o > 255) {
                    throw new IllegalArgumentException("octet out of range in " + ipv4);
                }
                value = (value << 8) | o;
            }
            return value;
        }
    }
}
