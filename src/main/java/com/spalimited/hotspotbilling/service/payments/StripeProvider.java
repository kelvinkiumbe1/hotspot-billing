package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stripe — cards worldwide, for operators outside mobile-money markets.
 *
 * <p>Its signature scheme is the strictest of the three and the easiest to
 * implement wrongly. The header carries a timestamp and one or more signatures;
 * the signed payload is the timestamp, a full stop, then the raw body — not the
 * body alone. A tolerance on the timestamp is part of the check, not an extra:
 * without it a valid old webhook can be replayed forever.
 *
 * <p>Its API takes form-encoded bodies rather than JSON, and amounts in the
 * currency's smallest unit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StripeProvider implements PaymentProvider {

    // Address moved to PaymentEndpoints; the default there is this URL.

    /** How stale a signed webhook may be. Stripe's own default. */
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    /**
     * Currencies Stripe quotes whole. Every other currency is multiplied by a
     * hundred, so this list decides whether a customer is charged the right
     * amount or a hundred times it.
     */
    private static final java.util.Set<String> ZERO_DECIMAL = java.util.Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG",
            "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

    private final PaymentGatewayService gateways;
    private final ObjectMapper mapper;
    private final PaymentEndpoints endpoints;
    /**
     * Built per call rather than frozen at construction, so the address can be
     * stood in front of by a test. Every other rail here already does this.
     */
    private RestClient client() {
        return RestClient.create(endpoints.stripe());
    }

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.STRIPE;
    }

    @Override
    public boolean usable() {
        return config() != null;
    }

    private PaymentGateway config() {
        return gateways.find(PaymentGateway.Kind.STRIPE)
                .filter(PaymentGateway::isActive)
                .filter(g -> g.getSecretKey() != null && !g.getSecretKey().isBlank())
                .orElse(null);
    }

    @Override
    public Charge charge(ChargeRequest request) {
        PaymentGateway cfg = config();
        if (cfg == null) {
            throw new IllegalStateException("Stripe is not configured");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "payment");
        form.put("client_reference_id", request.reference());
        form.put("line_items[0][quantity]", "1");
        form.put("line_items[0][price_data][currency]", request.currency().toLowerCase());
        form.put("line_items[0][price_data][unit_amount]",
                String.valueOf(minorUnits(request.amount(), request.currency())));
        form.put("line_items[0][price_data][product_data][name]",
                request.description() == null ? "Internet access" : request.description());
        // Stripe requires return URLs. They are deliberately relative to the
        // portal rather than configured separately: one more setting to keep in
        // sync is one more way for a checkout to dead-end.
        form.put("success_url", "{PORTAL}/?paid=1");
        form.put("cancel_url", "{PORTAL}/?cancelled=1");

        JsonNode response;
        try {
            response = client().post()
                    .uri("/checkout/sessions")
                    .header("Authorization", "Bearer " + cfg.getSecretKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(encode(form))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Stripe checkout session failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the card payment. Please try again.");
        }
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Stripe refused the payment: "
                    + (response == null ? "no response" : response.path("error").path("message").asString("")));
        }
        return new Charge(response.path("id").asString(null), response.path("url").asString(null));
    }

    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        PaymentGateway cfg = config();
        if (cfg == null) {
            throw Signatures.reject("Stripe", "not configured");
        }
        String secret = cfg.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw Signatures.reject("Stripe", "no endpoint secret is configured");
        }
        verify(rawBody, Signatures.header(headers, "Stripe-Signature"), secret);

        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw Signatures.reject("Stripe", "body was not readable JSON");
        }
        String type = event.path("type").asString("");
        JsonNode object = event.path("data").path("object");
        String providerRef = object.path("id").asString(null);
        String reference = object.path("client_reference_id").asString(null);
        String currency = object.path("currency").asString("");

        return switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                    Optional.of(new Settlement(providerRef, reference, true,
                            majorUnits(object.path("amount_total").asLong(0), currency),
                            currency.toUpperCase(), object.path("payment_intent").asString(null), null));
            case "checkout.session.expired", "checkout.session.async_payment_failed" ->
                    Optional.of(new Settlement(providerRef, reference, false, null, null, null,
                            "the checkout was not completed"));
            default -> {
                log.debug("Stripe event {} needs no action", type);
                yield Optional.empty();
            }
        };
    }

    /**
     * Stripe's scheme: {@code t=<unix>,v1=<hex>[,v1=<hex>]}. The signed payload
     * is {@code t + "." + body}. More than one v1 can be present during a secret
     * rotation, and any of them matching is a genuine delivery.
     */
    void verify(byte[] rawBody, String header, String secret) {
        if (header == null || header.isBlank()) {
            throw Signatures.reject("Stripe", "no Stripe-Signature header");
        }
        String timestamp = null;
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                timestamp = kv[1];
            } else if ("v1".equals(kv[0])) {
                candidates.add(kv[1]);
            }
        }
        if (timestamp == null || candidates.isEmpty()) {
            throw Signatures.reject("Stripe", "signature header was malformed");
        }
        // Replay protection is part of the check. Without it, one captured
        // "payment succeeded" can be posted back forever, each time free.
        try {
            Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(signedAt, Instant.now()).abs().compareTo(TOLERANCE) > 0) {
                throw Signatures.reject("Stripe", "signature is outside the accepted time window");
            }
        } catch (NumberFormatException e) {
            throw Signatures.reject("Stripe", "signature timestamp was not a number");
        }

        byte[] signed = (timestamp + "." + new String(rawBody == null ? new byte[0] : rawBody,
                StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String expected = Signatures.hmacHex("HmacSHA256", secret, signed);
        for (String candidate : candidates) {
            if (Signatures.matches(expected, candidate)) {
                return;
            }
        }
        throw Signatures.reject("Stripe", "no signature matched");
    }

    static long minorUnits(BigDecimal amount, String currency) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        if (ZERO_DECIMAL.contains(currency == null ? "" : currency.toUpperCase())) {
            return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static BigDecimal majorUnits(long minor, String currency) {
        if (ZERO_DECIMAL.contains(currency == null ? "" : currency.toUpperCase())) {
            return BigDecimal.valueOf(minor);
        }
        return BigDecimal.valueOf(minor).movePointLeft(2);
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    // --- Recurring ---

    /** Separates the customer from the payment method in a stored token. */
    static final String TOKEN_SEPARATOR = "/";

    @Override
    public boolean supportsRecurring() {
        return true;
    }

    /**
     * The payment method a completed checkout saved.
     *
     * <p>Both halves are returned, separated, because charging needs the
     * customer and the payment method together and this interface carries one
     * string. A payment method with no customer cannot be charged again, so
     * half a pair is treated as none.
     */
    @Override
    public Optional<String> reusableToken(byte[] rawBody) {
        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            return Optional.empty();
        }
        JsonNode object = event.path("data").path("object");
        String method = object.path("payment_method").asString(null);
        String customer = object.path("customer").asString(null);
        if (method == null || method.isBlank() || customer == null || customer.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(customer + TOKEN_SEPARATOR + method);
    }

    @Override
    public Charge chargeStored(String token, ChargeRequest request) {
        PaymentGateway cfg = config();
        if (cfg == null) {
            throw new IllegalStateException("Stripe is not configured");
        }
        int slash = token == null ? -1 : token.indexOf(TOKEN_SEPARATOR);
        if (slash <= 0) {
            throw new IllegalStateException("That saved card is not usable, because it was "
                    + "stored without the customer it belongs to");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", String.valueOf(minorUnits(request.amount(), request.currency())));
        form.put("currency", request.currency().toLowerCase());
        form.put("customer", token.substring(0, slash));
        form.put("payment_method", token.substring(slash + 1));
        // The three that make this a charge rather than an attempt: confirm now,
        // the customer is not here, and do not try to redirect anybody.
        form.put("confirm", "true");
        form.put("off_session", "true");
        form.put("automatic_payment_methods[enabled]", "true");
        form.put("automatic_payment_methods[allow_redirects]", "never");
        form.put("metadata[reference]", request.reference());

        JsonNode response;
        try {
            response = client().post()
                    .uri("/payment_intents")
                    .header("Authorization", "Bearer " + cfg.getSecretKey())
                    .header("Idempotency-Key", request.reference())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(encode(form))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stripe would not charge the saved card: "
                    + e.getMessage());
        }
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Stripe refused the renewal: "
                    + (response == null ? "no response"
                            : response.path("error").path("message").asString("")));
        }
        String status = response.path("status").asString("");
        if (!"succeeded".equals(status)) {
            // requires_action means the card wants the customer present for a
            // 3DS step nobody can complete at two in the morning. It is a
            // decline for this purpose, and calling it anything else strands
            // the renewal in a state no job will ever resolve.
            throw new IllegalStateException("The saved card needs the customer: " + status);
        }
        return new Charge(response.path("id").asString(request.reference()), null);
    }
}
