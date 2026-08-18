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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Airtel Money — one integration, fourteen markets, and a prompt on the handset.
 *
 * <p>Fills the gaps MTN does not reach. Malawi, Tanzania and the DRC were all
 * going through Flutterwave: an aggregator fee and a checkout page for a
 * customer who only ever wanted to type a PIN. Airtel's Collections API does a
 * USSD push, so those markets get the same flow as Kenya.
 *
 * <h2>The detail that breaks everything silently</h2>
 *
 * <p>Airtel wants the <em>national</em> number, without the country code:
 * {@code 751234567}, not {@code 254751234567}. Everything else in this system
 * stores the full international form, so the dialling code has to come off on
 * the way out. Send the full number and Airtel accepts the request and then
 * fails to find the subscriber — a charge that looks sent and never arrives.
 *
 * <h2>Status codes, which are two letters and not obvious</h2>
 *
 * <p>{@code TS} succeeded, {@code TF} failed, and {@code TA} and {@code TIP}
 * both mean "still happening". Treating an ambiguous transaction as failed
 * cancels a sale from a customer mid-PIN.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AirtelProvider implements PaymentProvider {

    private static final String SANDBOX = "https://openapiuat.airtel.africa";
    private static final String PRODUCTION = "https://openapi.airtel.africa";

    /**
     * The markets Airtel Money serves that this system also knows about.
     *
     * <p>Airtel is in fourteen countries; these are the ones in the country
     * table. A country outside this set refuses rather than sending a charge
     * into a market the operator has no agreement in.
     */
    private static final Set<Country> MARKETS = Set.of(
            Country.KE, Country.TZ, Country.UG, Country.RW,
            Country.ZM, Country.MW, Country.CD, Country.NG,
            Country.NE, Country.TD, Country.GA, Country.CG, Country.MG);

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;

    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.AIRTEL_MONEY;
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
        // Airtel takes the amount as a bare number in the market's currency. If
        // the prices are written in a different one, that number means something
        // else — silently, and about money.
        return MarketGuard.currencyAgrees("Airtel Money", country(),
                portalSettings.settings().getCurrencyCode());
    }

    @Override
    public boolean pollable() {
        return true;
    }

    private record Config(String baseUrl, String clientId, String clientSecret,
                          Country country, String currency) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.AIRTEL_MONEY)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getConsumerKey()) || blank(g.getConsumerSecret())) {
            return null;
        }
        Country country = country();
        if (!MARKETS.contains(country)) {
            return null;
        }
        boolean live = g.getEnvironment() == PaymentGateway.Environment.PRODUCTION;
        return new Config(live ? PRODUCTION : SANDBOX,
                g.getConsumerKey().trim(), g.getConsumerSecret().trim(),
                country, country.currency());
    }

    private Country country() {
        try {
            return Country.of(portalSettings.settings().getCountry());
        } catch (Exception e) {
            return Country.KE;
        }
    }

    /** True where Airtel Money is worth offering at all. */
    public boolean availableHere() {
        return MARKETS.contains(country());
    }

    /**
     * The national number, with the dialling code removed.
     *
     * <p>The single most consequential line in this class. Airtel identifies a
     * subscriber by their national number; given the international form it
     * accepts the request and then cannot find them, which reads as a charge
     * that was sent and simply never arrived.
     */
    static String msisdn(String phoneNumber, Country country) {
        if (phoneNumber == null) {
            return null;
        }
        String digits = phoneNumber.replaceAll("\\D", "");
        String code = country.diallingCode();
        if (!code.isEmpty() && digits.startsWith(code)) {
            return digits.substring(code.length());
        }
        return digits;
    }

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = config();
        if (cfg == null || !currencyAgrees()) {
            throw new IllegalStateException("Airtel Money is not set up for this country");
        }
        // Ours, generated before the call, because everything afterwards is
        // keyed on it and a request that times out having reached Airtel still
        // has to be findable.
        String transactionId = UUID.randomUUID().toString();
        String national = msisdn(request.phoneNumber(), cfg.country());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", trim(request.description(), 60));
        body.put("subscriber", Map.of(
                "country", cfg.country().name(),
                "currency", cfg.currency(),
                "msisdn", national));
        body.put("transaction", Map.of(
                // Whole units. Airtel is not a minor-unit rail.
                "amount", request.amount().setScale(0, RoundingMode.HALF_UP).toPlainString(),
                "country", cfg.country().name(),
                "currency", cfg.currency(),
                "id", transactionId));

        JsonNode response;
        try {
            response = client(cfg).post()
                    .uri("/merchant/v1/payments/")
                    .header("Authorization", "Bearer " + token(cfg))
                    .header("X-Country", cfg.country().name())
                    .header("X-Currency", cfg.currency())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Airtel payment request failed: {}", e.getMessage());
            throw new IllegalStateException("Could not send the payment request. Please try again.");
        }
        if (!accepted(response)) {
            throw new IllegalStateException("Airtel refused the payment: " + message(response));
        }
        // No checkout URL: the customer is looking at a PIN prompt.
        return new Charge(transactionId, null);
    }

    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode response;
        try {
            response = client(cfg).get()
                    .uri("/standard/v1/payments/{id}", providerRef)
                    .header("Authorization", "Bearer " + token(cfg))
                    .header("X-Country", cfg.country().name())
                    .header("X-Currency", cfg.currency())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Airtel enquiry for {} failed: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(response, providerRef);
    }

    /** Airtel's enquiry document, turned into a verdict. */
    static Optional<Settlement> read(JsonNode response, String providerRef) {
        if (response == null) {
            return Optional.empty();
        }
        JsonNode transaction = response.path("data").path("transaction");
        // Airtel calls it status here and status_code on the callback.
        String state = transaction.path("status").asString(
                transaction.path("status_code").asString(""));
        String receipt = transaction.path("airtel_money_id").asString(null);
        String message = transaction.path("message").asString(null);

        return switch (state.toUpperCase()) {
            // Null, not zero. Airtel's enquiry does not return the amount,
            // and claiming zero meant every successful payment that arrived by
            // webhook hit PaymentService's amount check, failed it, and was
            // marked FAILED — customer charged, voucher never issued, and the
            // row no longer PENDING so reconciliation would not retry it.
            case "TS", "SUCCESS", "SUCCESSFUL" -> Optional.of(new Settlement(
                    providerRef, transaction.path("id").asString(providerRef),
                    true, null, null, receipt, null));
            case "TF", "FAILED" -> Optional.of(new Settlement(
                    providerRef, transaction.path("id").asString(providerRef),
                    false, null, null, null,
                    message == null ? "declined" : message));
            // TA is ambiguous and TIP is in progress. Both mean the customer is
            // still deciding, and calling either a failure cancels a live sale.
            default -> Optional.empty();
        };
    }

    /**
     * A callback from Airtel.
     *
     * <p>Airtel offers an optional hash, but its presence and shape vary by
     * market, so the body is read only far enough to learn which charge it
     * concerns and the verdict comes from an enquiry. Believing an unverified
     * body would let anyone who learned a transaction id mark a payment paid.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String id = referenceIn(rawBody);
        if (id == null || id.isBlank()) {
            throw Signatures.reject("Airtel", "no transaction id to check");
        }
        return poll(id);
    }

    /** The transaction id in a callback body. */
    static String referenceIn(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        // Read out rather than parsed: nothing in this body is trusted enough
        // to deserve a model, and the id is only used to ask Airtel a question.
        int at = body.indexOf("\"id\"");
        if (at < 0) {
            return null;
        }
        int colon = body.indexOf(':', at);
        int start = body.indexOf('"', colon) + 1;
        int end = body.indexOf('"', start);
        return colon > 0 && start > 0 && end > start ? body.substring(start, end) : null;
    }

    // --- plumbing ---

    private RestClient client(Config cfg) {
        return RestClient.create(cfg.baseUrl());
    }

    /** Airtel wraps every outcome in a status object; the HTTP code is not it. */
    static boolean accepted(JsonNode response) {
        if (response == null) {
            return false;
        }
        JsonNode status = response.path("status");
        if (status.path("success").isBoolean()) {
            return status.path("success").asBoolean(false);
        }
        return "200".equals(status.path("code").asString(""));
    }

    static String message(JsonNode response) {
        if (response == null) {
            return "no response";
        }
        String message = response.path("status").path("message").asString(null);
        return message != null ? message : "refused";
    }

    private String token(Config cfg) {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        JsonNode response = client(cfg).post()
                .uri("/auth/oauth2/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("client_id", cfg.clientId(),
                        "client_secret", cfg.clientSecret(),
                        "grant_type", "client_credentials"))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("access_token").asString(null) == null) {
            throw new IllegalStateException("Airtel would not issue a token — check the client id "
                    + "and secret, and that the app is approved for Collections");
        }
        token = response.path("access_token").asString(null);
        // Airtel returns expires_in as a string in some markets and a number in
        // others, so it is read defensively rather than trusted to be one.
        long seconds;
        try {
            seconds = Long.parseLong(response.path("expires_in").asString("3600"));
        } catch (NumberFormatException e) {
            seconds = 3600;
        }
        tokenExpiresAt = Instant.now().plus(Duration.ofSeconds(Math.max(60, seconds - 60)));
        return token;
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return "Internet";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
