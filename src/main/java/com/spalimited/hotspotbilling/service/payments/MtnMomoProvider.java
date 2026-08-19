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
import java.util.UUID;

/**
 * MTN Mobile Money — the same prompt-on-the-handset flow as M-Pesa, for the
 * dozen countries where MTN is the money.
 *
 * <p>This is the highest-leverage rail after Daraja, and the reason is shape
 * rather than size: MTN's <em>RequestToPay</em> is functionally STK Push. The
 * customer gets a prompt, enters their PIN, and never sees a web page. Paystack
 * and Flutterwave reach the same wallets but put a checkout page in front of
 * them; this does not, so the portal flow the product is already built around
 * works unchanged in Accra, Kampala, Kigali, Lusaka, Douala and Abidjan.
 *
 * <h2>Three ways it differs from Daraja, all of which matter</h2>
 *
 * <p><b>We invent the transaction id.</b> Daraja hands back a
 * CheckoutRequestID; MTN takes a UUID we generate in {@code X-Reference-Id} and
 * that <em>is</em> the transaction. Everything afterwards is keyed on it, so it
 * is generated before the call rather than read from the reply.
 *
 * <p><b>The callback cannot be trusted.</b> MTN does not sign it, and it only
 * fires where the callback host has been registered against the API user.
 * Anyone who learned a reference could otherwise post "SUCCESSFUL" and be
 * believed. So a callback is treated purely as a hint to go and ask:
 * {@link #settle} re-queries the status endpoint and reports what MTN says,
 * never what the body claimed.
 *
 * <p><b>Amounts are major units, as a string.</b> "100" is a hundred cedis, not
 * one. Sending minor units the way Paystack wants would overcharge a hundredfold,
 * so nothing here multiplies.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MtnMomoProvider implements PaymentProvider {

    // Both addresses moved to PaymentEndpoints, defaulting to these values.

    /**
     * MTN's own name for each market, sent as {@code X-Target-Environment}.
     *
     * <p>Derived from the operator's country rather than typed in: it is not a
     * preference, it is a fact about where they are, and a mistyped one fails
     * every charge with an error that does not say why.
     */
    private static final Map<Country, String> TARGETS = Map.of(
            Country.GH, "mtnghana",
            Country.UG, "mtnuganda",
            Country.RW, "mtnrwanda",
            Country.ZM, "mtnzambia",
            Country.CM, "mtncameroon",
            Country.CI, "mtnivorycoast");

    private final PaymentGatewayService gateways;
    private final PaymentEndpoints endpoints;
    private final PortalSettingsService portalSettings;

    /** Tokens last an hour; fetching one per charge would be slow and rude. */
    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.MTN_MOMO;
    }

    @Override
    public boolean usable() {
        return config() != null;
    }

    @Override
    public boolean pollable() {
        return true;
    }

    /** Everything one call needs, or null when the gateway is not set up. */
    private record Config(String baseUrl, String subscriptionKey, String apiUser,
                          String apiKey, String target, boolean live) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.MTN_MOMO)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getSecretKey()) || blank(g.getConsumerKey())
                || blank(g.getConsumerSecret())) {
            return null;
        }
        boolean live = g.getEnvironment() == PaymentGateway.Environment.PRODUCTION;
        // The operator's own value wins. MTN issues a target environment during
        // merchant onboarding and it is not always the "mtn<country>" the older
        // markets use, so a market this code has no entry for is reachable by
        // pasting it rather than waiting for someone to guess the string.
        String target = live ? targetFor(country(), g.getShortCode()) : "sandbox";
        if (target == null) {
            // Live, but in a country MTN MoMo does not serve. Refusing here is
            // better than sending a charge that fails for an unexplained reason.
            return null;
        }
        return new Config(endpoints.mtn(live),
                g.getSecretKey().trim(), g.getConsumerKey().trim(), g.getConsumerSecret().trim(),
                target, live);
    }

    private Country country() {
        try {
            return Country.of(portalSettings.settings().getCountry());
        } catch (Exception e) {
            return Country.GH;
        }
    }

    /**
     * The target environment header MTN expects.
     *
     * <p>MTN issues this per merchant, and for the older markets it is
     * predictably {@code mtnghana}, {@code mtnuganda} and so on. For Benin,
     * Eswatini, South Sudan and anywhere MTN opens next it is not something this
     * code can derive — and a wrong value is refused with an error that says
     * nothing about why. So a configured value always wins, and a market with
     * neither is refused rather than guessed at.
     */
    static String targetFor(Country country, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return TARGETS.get(country);
    }

    /**
     * True where MTN MoMo is worth offering at all.
     *
     * <p>Takes the configured target into account: an operator in a market this
     * code has no entry for has MTN available the moment they paste theirs.
     */
    public boolean availableHere() {
        String configured = gateways.find(PaymentGateway.Kind.MTN_MOMO)
                .map(PaymentGateway::getShortCode).orElse(null);
        Country here = country();
        if (targetFor(here, configured) != null) {
            return true;
        }
        // Benin, Eswatini and South Sudan all name MTN MoMo as their rail and
        // all three land here, because MTN issues the target environment per
        // merchant and this code cannot derive it. Silence was the problem: the
        // country table recommended MTN, the gateway saved, and the rail simply
        // never appeared with nothing anywhere saying why.
        log.warn("MTN MoMo is switched on but not offered: MTN issues a target environment per "
                + "merchant and there is none saved for {}. It is on your MoMo developer profile, "
                + "and goes in Target environment under Settings → Payment gateways.",
                here.countryName());
        return false;
    }

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = config();
        if (cfg == null) {
            throw new IllegalStateException("MTN MoMo is not set up for this country");
        }
        // Ours, not theirs, and generated before the call so the charge is
        // still identifiable if the request times out having nonetheless
        // reached MTN.
        String reference = UUID.randomUUID().toString();

        Map<String, Object> body = new LinkedHashMap<>();
        // Major units, as a string. MTN reads "100" as a hundred cedis;
        // multiplying by a hundred the way card processors want charges a fortune.
        body.put("amount", request.amount().setScale(0, RoundingMode.HALF_UP).toPlainString());
        // The sandbox only settles in EUR, whatever the operator's currency is.
        body.put("currency", cfg.live() ? request.currency() : "EUR");
        body.put("externalId", request.reference());
        body.put("payer", Map.of("partyIdType", "MSISDN", "partyId", request.phoneNumber()));
        body.put("payerMessage", trim(request.description(), 160));
        body.put("payeeNote", trim(request.reference(), 160));

        try {
            client(cfg).post()
                    .uri("/collection/v1_0/requesttopay")
                    .header("Authorization", "Bearer " + token(cfg))
                    .header("X-Reference-Id", reference)
                    .header("X-Target-Environment", cfg.target())
                    .header("Ocp-Apim-Subscription-Key", cfg.subscriptionKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("MTN MoMo requesttopay failed: {}", e.getMessage());
            throw new IllegalStateException("Could not send the payment request. Please try again.");
        }
        // No checkout URL: the customer is already looking at a PIN prompt.
        return new Charge(reference, null);
    }

    /**
     * What MTN says about a charge — the only trustworthy answer, and the one
     * both the callback and the reconcile sweep go through.
     */
    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode status;
        try {
            status = client(cfg).get()
                    .uri("/collection/v1_0/requesttopay/{ref}", providerRef)
                    .header("Authorization", "Bearer " + token(cfg))
                    .header("X-Target-Environment", cfg.target())
                    .header("Ocp-Apim-Subscription-Key", cfg.subscriptionKey())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("MTN MoMo status for {} unavailable: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(status, providerRef);
    }

    /** The status document, turned into a verdict. Package-private so it can be tested. */
    static Optional<Settlement> read(JsonNode status, String providerRef) {
        if (status == null) {
            return Optional.empty();
        }
        String state = status.path("status").asString("");
        String externalId = status.path("externalId").asString(null);
        BigDecimal amount = amountOf(status);

        // PENDING is not an outcome. Reporting it as "not paid" would fail a
        // customer who is still typing their PIN.
        return switch (state) {
            case "SUCCESSFUL" -> Optional.of(new Settlement(
                    providerRef, externalId, true, amount,
                    status.path("currency").asString(null),
                    status.path("financialTransactionId").asString(providerRef), null));
            case "FAILED", "REJECTED", "TIMEOUT" -> Optional.of(new Settlement(
                    providerRef, externalId, false, amount,
                    status.path("currency").asString(null), null, reason(status)));
            default -> Optional.empty();
        };
    }

    /**
     * A callback from MTN, which proves nothing on its own.
     *
     * <p>Unsigned, so the body is read only far enough to learn which charge it
     * concerns; the verdict comes from asking MTN directly. Believing the body
     * would let anyone who learned a reference mark a payment successful.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String reference = referenceIn(rawBody, headers);
        if (reference == null || reference.isBlank()) {
            throw Signatures.reject("MTN MoMo", "no reference to check");
        }
        return poll(reference);
    }

    /** The charge a callback is about: the header first, the body as a fallback. */
    static String referenceIn(byte[] rawBody, Map<String, String> headers) {
        String fromHeader = Signatures.header(headers, "x-reference-id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        // Some markets put it in the body instead. Read out rather than parsed,
        // because this value is only ever used to ask MTN a question — nothing
        // in the body is trusted enough to deserve a model.
        String body = new String(rawBody, StandardCharsets.UTF_8);
        for (String key : new String[]{"\"referenceId\"", "\"externalId\""}) {
            int at = body.indexOf(key);
            if (at < 0) {
                continue;
            }
            int colon = body.indexOf(':', at);
            int start = body.indexOf('"', colon) + 1;
            int end = body.indexOf('"', start);
            if (colon > 0 && start > 0 && end > start) {
                return body.substring(start, end);
            }
        }
        return null;
    }

    // --- plumbing ---

    private RestClient client(Config cfg) {
        return RestClient.create(cfg.baseUrl());
    }

    /**
     * A bearer token, cached until shortly before it expires.
     *
     * <p>Basic auth of the API user and key, which is the one place those two
     * are used — every other call carries the bearer token instead.
     */
    private String token(Config cfg) {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        String basic = java.util.Base64.getEncoder().encodeToString(
                (cfg.apiUser() + ":" + cfg.apiKey()).getBytes(StandardCharsets.UTF_8));
        JsonNode response = client(cfg).post()
                .uri("/collection/token/")
                .header("Authorization", "Basic " + basic)
                .header("Ocp-Apim-Subscription-Key", cfg.subscriptionKey())
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("access_token").asString(null) == null) {
            throw new IllegalStateException("MTN MoMo would not issue a token — check the "
                    + "subscription key, API user and API key");
        }
        token = response.path("access_token").asString(null);
        // A minute short of what they said, so a token never expires mid-charge.
        long seconds = Math.max(60, response.path("expires_in").asInt(3600) - 60);
        tokenExpiresAt = Instant.now().plus(Duration.ofSeconds(seconds));
        return token;
    }

    private static BigDecimal amountOf(JsonNode status) {
        try {
            return new BigDecimal(status.path("amount").asString("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** MTN nests the failure reason, and sometimes states it flat. */
    private static String reason(JsonNode status) {
        JsonNode nested = status.path("reason");
        if (nested.isObject()) {
            String message = nested.path("message").asString(null);
            return message != null ? message : nested.path("code").asString("declined");
        }
        String flat = nested.asString(null);
        return flat != null ? flat : "declined";
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
