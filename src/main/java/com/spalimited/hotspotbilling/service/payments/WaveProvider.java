package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.i18n.Country;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wave — the cheapest way to be paid in Senegal.
 *
 * <p>Wave arrived charging a flat 1% where Orange Money was charging several
 * times that, and took a very large share of Senegalese mobile money doing it.
 * An operator in Dakar wants Wave and Orange Money both switched on, not a
 * choice between them, which is exactly what the several-gateways work was for.
 *
 * <h2>Amounts are strings, and XOF has no cents</h2>
 *
 * <p>Wave's API takes {@code "amount": "1000"} as a string in <em>major</em>
 * units. XOF and XAF have no minor unit at all, so the hundred-multiplier that
 * Stripe and Paystack need would charge a customer a hundred times the price.
 * Sent as a plain integer string for that reason.
 *
 * <h2>The one rail here that signs its webhooks properly</h2>
 *
 * <p>Wave uses Stripe's scheme: {@code Wave-Signature: t=<unix>,v1=<hex>}, with
 * the signed payload being the timestamp, a full stop, then the raw body — and a
 * timestamp tolerance, without which a captured "payment succeeded" can be
 * replayed forever. So unlike MTN, Airtel and Orange, the body can be believed
 * and the verdict does not need a second round trip.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WaveProvider implements PaymentProvider {

    private static final String BASE = "https://api.wave.com";

    /** How stale a signed webhook may be, matching Wave's own guidance. */
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    /**
     * The Wave markets this system also knows about.
     *
     * <p>Wave's markets, now that the country table names them. A market listed
     * here without its currency and dialling rules would offer a customer a rail
     * that cannot charge them, so adding one always means adding the country
     * properly rather than a name in this set.
     */
    private static final Set<Country> MARKETS = Set.of(
            Country.SN, Country.CI, Country.ML, Country.BF, Country.GM);

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PublicUrls urls;
    private final ObjectMapper mapper;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.WAVE;
    }

    @Override
    public boolean usable() {
        return config() != null && currencyAgrees();
    }

    /**
     * Whether the prices are written in the currency this rail collects in.
     *
     * <p>Deliberately not inside {@code config()}. Reading the outcome of a
     * payment that has already been started does not depend on the price
     * agreeing with anything — and if this blocked polling, an operator who
     * changed their currency would strand every payment already in flight,
     * timing them out as failed with the customers' money taken.
     */
    private boolean currencyAgrees() {
        return MarketGuard.currencyAgrees("Wave", country(),
                portalSettings.settings().getCurrencyCode());
    }

    @Override
    public boolean pollable() {
        return true;
    }

    private record Config(String apiKey, String webhookSecret, Country country, String currency) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.WAVE)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getSecretKey()) || blank(g.getWebhookSecret())) {
            return null;
        }
        Country country = country();
        if (!MARKETS.contains(country)) {
            return null;
        }
        return new Config(g.getSecretKey().trim(), g.getWebhookSecret().trim(),
                country, country.currency());
    }

    private Country country() {
        try {
            return Country.of(portalSettings.settings().getCountry());
        } catch (Exception e) {
            return Country.SN;
        }
    }

    /** True where Wave is worth offering at all. */
    public boolean availableHere() {
        return MARKETS.contains(country());
    }

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = config();
        if (cfg == null || !currencyAgrees()) {
            throw new IllegalStateException("Wave is not set up for this country");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // A string, in whole XOF. Wave rejects a number here.
        body.put("amount", request.amount().setScale(0, RoundingMode.HALF_UP).toPlainString());
        body.put("currency", cfg.currency());
        body.put("client_reference", request.reference());
        String base = urls.origin();
        if (base != null) {
            // Optional for Wave, unlike Orange — it has its own default landing
            // page. Sent when known so the customer comes back to the portal
            // rather than to a Wave screen with nothing to do next.
            body.put("success_url", base + "/?paid=" + enc(request.reference()));
            body.put("error_url", base + "/?failed=" + enc(request.reference()));
        }

        JsonNode response;
        try {
            response = client().post()
                    .uri("/v1/checkout/sessions")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    // Wave deduplicates on this, so a retry after a timeout
                    // cannot open a second checkout against the same sale.
                    .header("Idempotency-Key", request.reference())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Wave checkout session failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the Wave payment. Please try again.");
        }

        String id = response == null ? null : response.path("id").asString(null);
        String launch = response == null ? null : response.path("wave_launch_url").asString(null);
        if (id == null || launch == null) {
            throw new IllegalStateException("Wave refused the payment: "
                    + (response == null ? "no response"
                            : response.path("message").asString("refused")));
        }
        return new Charge(id, launch);
    }

    /**
     * Asks Wave about a checkout session.
     *
     * <p>Wave signs its webhooks, so this is not how a payment normally settles
     * — it is the safety net for a delivery that never arrived, which is what
     * the reconciliation sweep uses.
     */
    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode response;
        try {
            response = client().get()
                    .uri("/v1/checkout/sessions/{id}", providerRef)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Wave session lookup for {} failed: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(response);
    }

    /**
     * A checkout session document, turned into a verdict.
     *
     * <p>Two fields matter and they are not interchangeable. {@code
     * payment_status} is the money; {@code checkout_status} is the page. A
     * session can be {@code complete} with a payment still {@code processing},
     * and reading the page as the money issues a voucher for nothing.
     */
    static Optional<Settlement> read(JsonNode session) {
        if (session == null) {
            return Optional.empty();
        }
        String id = session.path("id").asString(null);
        String reference = session.path("client_reference").asString(null);
        String payment = session.path("payment_status").asString("").toLowerCase();
        String checkout = session.path("checkout_status").asString("").toLowerCase();
        BigDecimal amount = amountOf(session);

        if ("succeeded".equals(payment)) {
            return Optional.of(new Settlement(id, reference, true, amount,
                    session.path("currency").asString(null),
                    session.path("transaction_id").asString(id), null));
        }
        if ("cancelled".equals(payment) || "expired".equals(checkout)) {
            return Optional.of(new Settlement(id, reference, false, amount, null, null,
                    "expired".equals(checkout) ? "the payment page expired" : "cancelled"));
        }
        // "processing" with the page still open means the customer is mid-payment.
        return Optional.empty();
    }

    /**
     * A signed webhook from Wave.
     *
     * <p>Verified against the secret before anything in the body is read, then
     * believed — which is the whole benefit of a rail that signs properly.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        Config cfg = config();
        if (cfg == null) {
            throw Signatures.reject("Wave", "no webhook secret is configured");
        }
        verify(rawBody, Signatures.header(headers, "Wave-Signature"), cfg.webhookSecret());

        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw Signatures.reject("Wave", "body was not readable JSON");
        }
        String type = event.path("type").asString("");
        JsonNode data = event.path("data");

        return switch (type) {
            case "checkout.session.completed" -> read(data);
            case "checkout.session.payment_failed" -> Optional.of(new Settlement(
                    data.path("id").asString(null),
                    data.path("client_reference").asString(null),
                    false, amountOf(data), null, null,
                    data.path("last_payment_error").path("message").asString("declined")));
            default -> {
                log.debug("Wave event {} needs no action", type);
                yield Optional.empty();
            }
        };
    }

    /**
     * Wave's scheme, which is Stripe's: {@code t=<unix>,v1=<hex>}, signing
     * {@code t + "." + body}. Several v1 values can appear while a secret is
     * being rotated, and any of them matching is a genuine delivery.
     */
    void verify(byte[] rawBody, String header, String secret) {
        if (header == null || header.isBlank()) {
            throw Signatures.reject("Wave", "no Wave-Signature header");
        }
        String timestamp = null;
        List<String> candidates = new ArrayList<>();
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
            throw Signatures.reject("Wave", "signature header was malformed");
        }
        // Without the time window a single captured success can be posted back
        // forever, each time issuing a free voucher.
        try {
            Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(signedAt, Instant.now()).abs().compareTo(TOLERANCE) > 0) {
                throw Signatures.reject("Wave", "signature is outside the accepted time window");
            }
        } catch (NumberFormatException e) {
            throw Signatures.reject("Wave", "signature timestamp was not a number");
        }

        byte[] signed = (timestamp + "." + new String(rawBody == null ? new byte[0] : rawBody,
                StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String expected = Signatures.hmacHex("HmacSHA256", secret, signed);
        for (String candidate : candidates) {
            if (Signatures.matches(expected, candidate)) {
                return;
            }
        }
        throw Signatures.reject("Wave", "no signature matched");
    }

    /** Wave quotes amounts as strings in major units. */
    private static BigDecimal amountOf(JsonNode node) {
        try {
            return new BigDecimal(node.path("amount").asString("0"));
        } catch (RuntimeException e) {
            // Zero rather than null: a signed body with an unreadable amount
            // must still fail the amount check downstream rather than skip it.
            return BigDecimal.ZERO;
        }
    }

    // --- plumbing ---

    private RestClient client() {
        return RestClient.create(BASE);
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
