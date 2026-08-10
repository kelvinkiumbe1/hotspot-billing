package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Webhook;
import com.spalimited.hotspotbilling.repository.WebhookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Outbound webhooks. Events are dispatched off the request thread so a slow
 * or dead receiver never blocks a payment or a voucher issue, and each body
 * is signed with the webhook's secret so the receiver can trust it.
 *
 * <p>JSON is built by hand rather than through a mapper: the payloads are
 * small maps of strings and numbers we control, and the signature must be
 * over the exact bytes we send — hand-building keeps those two in lockstep.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    /** The events a webhook can subscribe to. */
    public static final List<String> EVENTS = List.of(
            "subscriber.created", "subscriber.paused", "subscriber.resumed",
            "payment.received", "payment.refunded",
            "voucher.generated", "voucher.redeemed",
            "ticket.opened", "ticket.resolved");

    private final WebhookRepository webhooks;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "webhook-dispatch");
        t.setDaemon(true);
        return t;
    });

    // --- Management ---

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return webhooks.findAllByOrderByCreatedAtDesc().stream()
                .map(w -> Map.<String, Object>of(
                        "id", w.getId(),
                        "label", w.getLabel(),
                        "url", w.getUrl(),
                        "events", Arrays.asList(w.getEvents().split(",")),
                        "active", w.isActive(),
                        "lastStatus", w.getLastStatus() == null ? "" : w.getLastStatus(),
                        "lastAttemptAt", w.getLastAttemptAt() == null ? "" : w.getLastAttemptAt()))
                .toList();
    }

    @Transactional
    public Webhook create(String label, String url, String secret, List<String> events, String createdBy) {
        List<String> valid = events == null ? List.of()
                : events.stream().filter(EVENTS::contains).distinct().toList();
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one event to send.");
        }
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new IllegalArgumentException("The URL must start with http:// or https://");
        }
        String sec = secret == null || secret.isBlank() ? randomSecret() : secret.trim();
        return webhooks.save(Webhook.builder()
                .label(label == null || label.isBlank() ? "Webhook" : label.trim())
                .url(url.trim())
                .secret(sec)
                .events(String.join(",", valid))
                .active(true)
                .createdBy(createdBy)
                .build());
    }

    @Transactional
    public void delete(Long id) {
        webhooks.deleteById(id);
    }

    /** Fire a sample event at one webhook so a consumer can verify wiring. */
    @Transactional
    public void sendTest(Long id) {
        Webhook w = webhooks.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown webhook"));
        deliver(w, "ping", Map.of("message", "This is a test event from Zidi."));
    }

    // --- Dispatch ---

    /** Sends an event to every active webhook subscribed to it. Non-blocking. */
    @Transactional(readOnly = true)
    public void dispatch(String event, Map<String, Object> data) {
        for (Webhook w : webhooks.findByActiveTrue()) {
            if (Arrays.asList(w.getEvents().split(",")).contains(event)) {
                deliver(w, event, data);
            }
        }
    }

    private void deliver(Webhook w, String event, Map<String, Object> data) {
        Long id = w.getId();
        String url = w.getUrl();
        String secret = w.getSecret();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", event);
        envelope.put("sentAt", Instant.now().toString());
        envelope.put("data", data);
        String body = toJson(envelope);
        pool.submit(() -> {
            int status = -1;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Zidi-Event", event)
                        .header("X-Zidi-Signature", "sha256=" + hmac(secret, body))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (Exception e) {
                log.warn("Webhook {} delivery to {} failed: {}", id, url, e.getMessage());
            } finally {
                recordAttempt(id, status);
            }
        });
    }

    @Transactional
    public void recordAttempt(Long id, int status) {
        webhooks.findById(id).ifPresent(w -> {
            w.setLastStatus(status < 0 ? null : status);
            w.setLastAttemptAt(Instant.now());
            webhooks.save(w);
        });
    }

    // --- helpers ---

    private static String randomSecret() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte x : sig) {
                hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<String, Object>) map).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return quote(v.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
