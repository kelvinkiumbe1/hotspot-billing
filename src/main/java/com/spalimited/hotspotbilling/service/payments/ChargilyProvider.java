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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chargily — Algeria.
 *
 * <p>Forty-six million people and, until now, nothing. Algeria has no mobile
 * money to speak of: people pay with EDAHABIA, the Algérie Poste card that tens
 * of millions hold, or with a CIB bank card, and both clear domestically through
 * SATIM. Stripe does not serve Algeria and no pan-African aggregator collects
 * dinars, so the only way in is an Algerian gateway. Chargily is the one with a
 * modern API rather than a bank integration project.
 *
 * <h2>What was checked</h2>
 *
 * <p>The endpoints are live: {@code /api/v2/checkouts} and {@code /api/v2/balance}
 * both answer {@code 401 {"message":"Unauthenticated."}} while an invented path
 * under the same prefix returns Chargily's 404 page. Authentication is checked
 * before the body, so unlike EMIS or WaafiPay the field names could not be
 * confirmed by probing — they come from Chargily's published API and the readers
 * below are written to be forgiving about where a value is found.
 *
 * <h2>The webhook is signed properly</h2>
 *
 * <p>HMAC-SHA256 over the raw body, in a {@code signature} header, keyed on the
 * same secret key. That puts Chargily in a small group here — with Stripe and
 * Wave — of rails whose callback can be trusted on its own rather than being a
 * hint to go and ask. The raw bytes matter: a reserialised copy would not verify.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChargilyProvider implements PaymentProvider {

    private static final Set<Country> MARKETS = Set.of(Country.DZ);

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final ObjectMapper mapper;
    private final PaymentEndpoints endpoints;
    private final PublicUrls urls;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.CHARGILY;
    }

    @Override
    public boolean usable() {
        return availableHere() && currencyAgrees() && secret() != null;
    }

    /**
     * Asking is possible, and worth it.
     *
     * <p>Chargily can be asked about a checkout, so a callback that never arrived
     * does not strand a payment. Unlike MTN this is a safety net rather than the
     * settlement path — the callback is signed and is believed.
     */
    @Override
    public boolean pollable() {
        return true;
    }

    /** True where Chargily is worth offering at all. */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("Chargily", MARKETS,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean currencyAgrees() {
        try {
            return MarketGuard.currencyAgrees("Chargily", Country.DZ,
                    portalSettings.settings().getCurrencyCode());
        } catch (Exception e) {
            return false;
        }
    }

    private String secret() {
        return gateways.find(PaymentGateway.Kind.CHARGILY)
                .filter(PaymentGateway::isActive)
                .map(PaymentGateway::getSecretKey)
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .orElse(null);
    }

    // ------------------------------------------------------------------ charge

    @Override
    public Charge charge(ChargeRequest request) {
        String secret = availableHere() && currencyAgrees() ? secret() : null;
        if (secret == null) {
            throw new IllegalStateException("Chargily is not set up for this country");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // Dinars, whole. Chargily takes the main unit rather than centimes, and
        // the dinar's subunit has not circulated in decades -- so a fractional
        // amount here would be refused rather than rounded.
        body.put("amount", dinars(request.amount()));
        body.put("currency", "dzd");
        // payment_method is deliberately not sent. Left out, Chargily shows the
        // customer both EDAHABIA and CIB and lets them pick; naming one would
        // shut out whichever card they actually hold, and EDAHABIA and CIB are
        // held by largely different people.
        body.put("description", trim(request.description(), 240));
        // Ours, and what comes back on the checkout and in the webhook.
        body.put("metadata", java.util.List.of(Map.of("reference", request.reference())));
        body.put("locale", "fr");

        String origin = urls.origin();
        if (origin != null && !origin.isBlank()) {
            body.put("success_url", origin + "/?paid=" + enc(request.reference()));
            body.put("failure_url", origin + "/?failed=" + enc(request.reference()));
            body.put("webhook_endpoint", origin + "/api/payments/chargily/webhook");
        } else {
            // success_url is required by Chargily, so something has to go here.
            // The payment still settles: the sweep asks.
            body.put("success_url", "https://example.invalid/paid");
            log.info("No public address configured, so Chargily gets no webhook — "
                    + "payments will settle on the reconcile sweep instead.");
        }

        JsonNode response;
        try {
            response = client().post()
                    .uri("/checkouts")
                    .header("Authorization", "Bearer " + secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Chargily checkout failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }

        String id = response == null ? null : response.path("id").asString(null);
        String url = response == null ? null : response.path("checkout_url").asString(null);
        if (id == null || id.isBlank() || url == null || url.isBlank()) {
            throw new IllegalStateException(refusal(response));
        }
        return new Charge(id, url);
    }

    /**
     * Dinars, as a whole number.
     *
     * <p>Not centimes. The dinar nominally has a hundred of them and none have
     * circulated in living memory, so Chargily works in dinars — and sending
     * centimes would multiply every price by a hundred.
     */
    static long dinars(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    // ----------------------------------------------------------------- outcome

    @Override
    public Optional<Settlement> poll(String providerRef) {
        String secret = secret();
        if (secret == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode checkout;
        try {
            checkout = client().get()
                    .uri("/checkouts/{id}", providerRef)
                    .header("Authorization", "Bearer " + secret)
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Chargily status for {} unavailable: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(checkout, providerRef);
    }

    /**
     * A checkout document, turned into a verdict.
     *
     * <p>{@code pending} is a customer still on Chargily's page and returns
     * empty. So does anything unrecognised, for the same reason it does on the
     * other rails whose vocabulary could not be confirmed: delaying a voucher is
     * recoverable and denying one is not.
     */
    static Optional<Settlement> read(JsonNode checkout, String providerRef) {
        if (checkout == null) {
            return Optional.empty();
        }
        String status = checkout.path("status").asString("");
        String reference = referenceIn(checkout);
        BigDecimal amount = amountOf(checkout);

        return switch (status.toLowerCase(Locale.ROOT)) {
            case "paid", "succeeded", "success" -> Optional.of(new Settlement(
                    providerRef, reference, true, amount, "DZD",
                    checkout.path("invoice_id").asString(providerRef), null));
            case "failed", "canceled", "cancelled", "expired" -> Optional.of(new Settlement(
                    providerRef, reference, false, amount, "DZD", null, status));
            default -> Optional.empty();
        };
    }

    /**
     * A Chargily webhook, verified.
     *
     * <p>HMAC-SHA256 over the exact bytes received. Reserialising the body first
     * would change the whitespace and fail every time, which is why the raw array
     * is what this takes.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String secret = secret();
        if (secret == null) {
            throw Signatures.reject("Chargily", "not configured");
        }
        String given = Signatures.header(headers, "signature");
        if (given == null || given.isBlank()) {
            given = Signatures.header(headers, "x-chargily-signature");
        }
        if (given == null || given.isBlank()) {
            throw Signatures.reject("Chargily", "no signature on the callback");
        }
        String expected = Signatures.hmacHex("HmacSHA256", secret,
                rawBody == null ? new byte[0] : rawBody);
        if (!Signatures.matches(expected, given)) {
            throw Signatures.reject("Chargily", "signature did not match");
        }

        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw Signatures.reject("Chargily", "the body was not readable");
        }
        // Chargily sends several event types down one endpoint. Only a checkout
        // reaching an end state settles anything.
        JsonNode checkout = event.path("data").isMissingNode() ? event : event.path("data");
        String id = checkout.path("id").asString(null);
        return read(checkout, id);
    }

    /** Our reference, out of the metadata Chargily carries back. */
    static String referenceIn(JsonNode checkout) {
        JsonNode metadata = checkout.path("metadata");
        if (metadata.isArray()) {
            for (JsonNode entry : metadata) {
                String value = entry.path("reference").asString(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        String flat = metadata.path("reference").asString(null);
        return flat != null && !flat.isBlank() ? flat : null;
    }

    // ---------------------------------------------------------------- plumbing

    private RestClient client() {
        return RestClient.create(endpoints.chargily());
    }

    private static BigDecimal amountOf(JsonNode checkout) {
        String raw = checkout.path("amount").asString(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Chargily's own words where it gave any. */
    private static String refusal(JsonNode response) {
        if (response != null) {
            // The field error first, and the wrapper only if there is none.
            // Chargily pairs "The given data was invalid." with the sentence that
            // actually helps -- "The amount must be at least 75." -- and reading
            // the wrapper leaves an operator with nothing to act on.
            JsonNode errors = response.path("errors");
            if (errors.isObject() && !errors.isEmpty()) {
                for (JsonNode field : errors) {
                    if (field.isArray() && !field.isEmpty()) {
                        String first = field.get(0).asString(null);
                        if (first != null && !first.isBlank()) {
                            return first;
                        }
                    }
                }
            }
            String message = response.path("message").asString(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return "Chargily would not open a checkout";
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
