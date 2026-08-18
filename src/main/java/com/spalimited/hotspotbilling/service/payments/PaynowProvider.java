package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Paynow — Zimbabwe, and the one non-telco rail here that prompts the handset.
 *
 * <p>Its Express Checkout sends an EcoCash or OneMoney PIN prompt straight to
 * the customer's phone. No page, no redirect: the same shape as M-Pesa and MTN
 * MoMo rather than the card processors. That is why Zimbabwe is worth having
 * as its own rail instead of being written off as unreachable, which is what
 * the country table wrongly said until now.
 *
 * <h2>Two things about Paynow that are unlike everything else here</h2>
 *
 * <p><b>It does not speak JSON.</b> Requests are form-encoded and replies come
 * back as URL-encoded {@code key=value&key=value} strings. Parsing them as
 * JSON returns nothing and looks exactly like a network failure.
 *
 * <p><b>Its callback is hashed, so it can actually be verified</b> — unlike
 * MTN's, which is unsigned. The hash is SHA-512 of every value concatenated in
 * the order Paynow documents, with the integration key appended, uppercased.
 * Order is part of the algorithm: the same values in a different order produce
 * a different hash and every message is rejected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaynowProvider implements PaymentProvider {

    private static final String BASE = "https://www.paynow.co.zw";

    /**
     * The fields that go into the hash, in Paynow's documented order.
     *
     * <p>Written down rather than derived from the map, because a hash over
     * "whatever keys happen to be present, in whatever order a HashMap gives
     * them" is right on the developer's machine and wrong in production.
     */
    private static final String[] INITIATE_ORDER = {
            "id", "reference", "amount", "additionalinfo", "returnurl", "resulturl", "status"};

    private static final String[] STATUS_ORDER = {
            "reference", "paynowreference", "amount", "status", "pollurl"};

    private final PaymentGatewayService gateways;
    private final RestClient client = RestClient.create(BASE);

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.PAYNOW;
    }

    @Override
    public boolean usable() {
        return config() != null;
    }

    @Override
    public boolean pollable() {
        return true;
    }

    /** Integration id and key, from the Paynow merchant dashboard. */
    private record Config(String id, String key) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.PAYNOW)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getConsumerKey()) || blank(g.getSecretKey())) {
            return null;
        }
        return new Config(g.getConsumerKey().trim(), g.getSecretKey().trim());
    }

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = config();
        if (cfg == null) {
            throw new IllegalStateException("Paynow is not set up");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", cfg.id());
        fields.put("reference", request.reference());
        // Paynow quotes in whole units with two decimals, never minor units.
        fields.put("amount", request.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        fields.put("additionalinfo", request.description() == null ? "" : request.description());
        fields.put("returnurl", BASE);
        fields.put("resulturl", BASE);
        fields.put("status", "Message");
        fields.put("hash", hash(fields, INITIATE_ORDER, cfg.key()));

        // Express Checkout when we know the wallet to prompt, the ordinary
        // redirect otherwise. The first is what makes this feel like M-Pesa.
        boolean express = request.phoneNumber() != null && !request.phoneNumber().isBlank();
        if (express) {
            fields.put("method", "ecocash");
            fields.put("phone", request.phoneNumber());
            fields.put("authemail", request.email() == null ? "" : request.email());
            // The hash covers the new fields too, so it is recomputed rather
            // than reused — a stale hash is rejected with no useful message.
            fields.remove("hash");
            fields.put("hash", hash(fields, expressOrder(), cfg.key()));
        }

        Map<String, String> reply = post(express ? "/interface/remotetransaction"
                : "/interface/initiatetransaction", fields);

        if (!"ok".equalsIgnoreCase(reply.getOrDefault("status", ""))) {
            String error = reply.getOrDefault("error", "Paynow refused the payment");
            throw new IllegalStateException(error);
        }
        // pollurl is how the outcome is learned, so it is what gets stored.
        String poll = reply.get("pollurl");
        if (poll == null || poll.isBlank()) {
            throw new IllegalStateException("Paynow accepted the payment but gave no way to check it");
        }
        // Express prompts the handset and has no page to send anyone to.
        return new Charge(poll, express ? null : reply.get("browserurl"));
    }

    /**
     * Asks Paynow how a charge ended, using the poll URL it gave us.
     *
     * <p>The reply is hashed the same way the callback is, and is checked the
     * same way — a poll URL is a guessable-ish thing to hold, and an unverified
     * "Paid" is an unverified "Paid" whichever direction it arrived from.
     */
    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> reply;
        try {
            String body = RestClient.create().get().uri(providerRef)
                    .retrieve().body(String.class);
            reply = parse(body);
        } catch (Exception e) {
            log.debug("Paynow poll failed for {}: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        if (!hashMatches(reply, STATUS_ORDER, cfg.key())) {
            log.warn("Paynow status for {} failed its hash — ignoring", providerRef);
            return Optional.empty();
        }
        return verdict(reply, providerRef);
    }

    /**
     * A result posted back by Paynow. Verified before it is believed.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        Config cfg = config();
        if (cfg == null) {
            throw Signatures.reject("Paynow", "not configured");
        }
        Map<String, String> reply = parse(
                rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8));
        if (!hashMatches(reply, STATUS_ORDER, cfg.key())) {
            throw Signatures.reject("Paynow", "hash did not match");
        }
        return verdict(reply, reply.get("pollurl"));
    }

    /** Paynow's own words for how a payment ended. */
    static Optional<Settlement> verdict(Map<String, String> reply, String providerRef) {
        String status = reply.getOrDefault("status", "").toLowerCase(Locale.ROOT);
        String reference = reply.get("reference");
        BigDecimal amount = amountOf(reply.get("amount"));
        String receipt = reply.get("paynowreference");

        return switch (status) {
            // "Awaiting Delivery" and "Delivered" both mean the money moved;
            // they describe a goods workflow this does not use.
            case "paid", "awaiting delivery", "delivered" -> Optional.of(new Settlement(
                    providerRef, reference, true, amount, null, receipt, null));
            case "cancelled", "failed", "disputed", "refunded" -> Optional.of(new Settlement(
                    providerRef, reference, false, amount, null, null, status));
            // "Created", "Sent" and anything unrecognised mean the customer is
            // still deciding. Calling that a failure cancels a live sale.
            default -> Optional.empty();
        };
    }

    // --- The hash, which is the whole of Paynow's security ---

    /**
     * SHA-512 of the listed values concatenated, plus the integration key,
     * uppercased hex.
     *
     * <p>Missing fields contribute an empty string rather than being skipped,
     * because Paynow hashes the value it holds and that value is "".
     */
    static String hash(Map<String, String> fields, String[] order, String integrationKey) {
        StringBuilder joined = new StringBuilder();
        for (String field : order) {
            String value = fields.get(field);
            joined.append(value == null ? "" : value);
        }
        joined.append(integrationKey);
        return sha512Hex(joined.toString()).toUpperCase(Locale.ROOT);
    }

    static boolean hashMatches(Map<String, String> reply, String[] order, String integrationKey) {
        String given = reply.get("hash");
        if (given == null || given.isBlank()) {
            return false;
        }
        String expected = hash(reply, order, integrationKey);
        // Constant-time: a hash compared byte by byte with an early exit leaks
        // how much of a guess was right.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                given.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    static String sha512Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-512 is required by Paynow and missing", e);
        }
    }

    // --- Their wire format, which is not JSON ---

    /**
     * Paynow answers with a URL-encoded {@code key=value&key=value} string.
     *
     * <p>Keys are lowercased on the way in: Paynow is inconsistent about case
     * between endpoints, and a hash computed over the wrong key set fails with
     * no indication that case was the reason.
     */
    static Map<String, String> parse(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        for (String pair : body.trim().split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8)
                    .trim().toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    private Map<String, String> post(String path, Map<String, String> fields) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        fields.forEach(form::add);
        try {
            String body = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return parse(body);
        } catch (Exception e) {
            log.warn("Paynow {} failed: {}", path, e.getMessage());
            throw new IllegalStateException("Could not reach Paynow. Please try again.");
        }
    }

    /** Express adds three fields, and they are hashed with the rest. */
    private static String[] expressOrder() {
        return new String[]{"id", "reference", "amount", "additionalinfo",
                "returnurl", "resulturl", "authemail", "phone", "method", "status"};
    }

    private static BigDecimal amountOf(String raw) {
        try {
            return raw == null ? BigDecimal.ZERO : new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
