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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Multicaixa Express — Angola, through EMIS's online payment gateway.
 *
 * <p>Angola was the last country in the table that read as supported and could
 * collect nothing: {@code Rail.NONE}, thirty-six million people, and a note
 * saying no built rail touched it. Multicaixa is the interbank network every
 * Angolan card and the Express wallet sit on, and EMIS — the company that runs
 * it — has a gateway.
 *
 * <h2>What was verified, and how</h2>
 *
 * <p>The paths and the request shape come from EMIS's own browser client rather
 * than from documentation. Its bundle names three operations on
 * {@code /v1/frameToken} — create, fetch, pay — and the live API confirms all
 * three: a request with a fake merchant token gets past body validation to
 * {@code {"code":"104","message":"invalid frame token"}}, which it could not do
 * with a field named wrongly, while an invented path returns a RESTEasy "could
 * not find resource". So the fields below are right and the endpoints are real.
 *
 * <p>What is <em>not</em> verified is the shape of a successful answer, because
 * that needs a merchant token. Every reader here is deliberately generous about
 * where it looks and strict about what it accepts: an unrecognised status reads
 * as "still going" rather than as failed, so the worst an unknown shape can do
 * is delay a voucher rather than deny one.
 *
 * <h2>How a payment goes</h2>
 *
 * <p>Not a redirect to a hosted page and not a handset push, but a frame: we ask
 * EMIS for a frame id, the customer opens EMIS's own page carrying that id, and
 * they confirm in the Multicaixa Express app on their phone. So there is a
 * checkout URL, and it is on EMIS's host.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MulticaixaProvider implements PaymentProvider {

    private static final Set<Country> MARKETS = Set.of(Country.AO);

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PaymentEndpoints endpoints;
    private final PublicUrls urls;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.MULTICAIXA;
    }

    @Override
    public boolean usable() {
        return availableHere() && currencyAgrees() && config() != null;
    }

    /**
     * There is a status endpoint, and it is what settles this rail.
     *
     * <p>EMIS's own client fetches {@code GET /v1/frameToken/{id}}, so asking is
     * a first-class operation here rather than a guess. The callback is treated
     * as a hint to go and ask, the way MTN's is, because nothing signs it.
     */
    @Override
    public boolean pollable() {
        return true;
    }

    /** True where Multicaixa is worth offering at all. */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("Multicaixa Express", MARKETS,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean currencyAgrees() {
        try {
            return MarketGuard.currencyAgrees("Multicaixa Express", Country.AO,
                    portalSettings.settings().getCurrencyCode());
        } catch (Exception e) {
            return false;
        }
    }

    private record Config(String merchantToken) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.MULTICAIXA)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getSecretKey())) {
            return null;
        }
        return new Config(g.getSecretKey().trim());
    }

    // ------------------------------------------------------------------ charge

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = availableHere() && currencyAgrees() ? config() : null;
        if (cfg == null) {
            throw new IllegalStateException("Multicaixa Express is not set up for this country");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // Ours, and what the frame quotes back.
        body.put("reference", request.reference());
        // Kwanzas, major units, as a string. The live API accepted exactly this.
        body.put("amount", request.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        body.put("token", cfg.merchantToken());
        // Express is the wallet Angolans have on their phone. Cards are disabled
        // deliberately: enabling them needs a separate agreement with EMIS, and a
        // method offered without one fails at the last step.
        body.put("mobile", "PAYMENT");
        body.put("card", "DISABLED");
        body.put("qrCode", "PAYMENT");

        String origin = urls.origin();
        if (origin != null && !origin.isBlank()) {
            body.put("callbackUrl", origin + "/api/payments/multicaixa/webhook");
        } else {
            // Not fatal: EMIS can be asked, so the sweep settles it a minute
            // later rather than never.
            log.info("No public address configured, so EMIS gets no callback URL — "
                    + "Multicaixa payments will settle on the reconcile sweep instead.");
        }

        JsonNode response;
        try {
            response = client().post()
                    .uri("/v1/frameToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // EMIS answers a refusal with a 400 and its reason in the
                    // body. Letting the status throw discards the reason.
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("EMIS frameToken failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }

        String frameId = frameId(response);
        if (frameId == null) {
            throw new IllegalStateException(refusal(response));
        }
        // EMIS's own page, carrying the frame id. Confirmed to answer 200 with
        // the frame application; /frame?token= is a 404 and was the wrong guess.
        return new Charge(frameId, endpoints.multicaixa() + "/?token="
                + java.net.URLEncoder.encode(frameId, StandardCharsets.UTF_8));
    }

    /**
     * The frame id out of a create response.
     *
     * <p>{@code id} is what EMIS's client calls it. The alternatives are checked
     * because this is the one field whose name could not be confirmed without a
     * merchant token, and getting it wrong would mean every payment refused with
     * EMIS's own message even though EMIS had accepted it.
     */
    static String frameId(JsonNode response) {
        if (response == null) {
            return null;
        }
        for (String field : new String[]{"id", "frameToken", "token", "frameId"}) {
            String value = response.path(field).asString(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- outcome

    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode response;
        try {
            response = client().get()
                    .uri("/v1/frameToken/{id}", providerRef)
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("EMIS status for {} unavailable: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(response, providerRef);
    }

    /**
     * A frame document, turned into a verdict.
     *
     * <p>Strict about what counts as an answer and lenient about what counts as
     * unfinished, because the exact vocabulary here is the part that could not be
     * confirmed without a merchant account. An unknown status returns empty,
     * which means the sweep asks again and eventually times the payment out — a
     * delay rather than a customer wrongly told their payment failed.
     */
    static Optional<Settlement> read(JsonNode response, String providerRef) {
        if (response == null) {
            return Optional.empty();
        }
        // A refusal from the gateway rather than news about the payment.
        if (!response.path("code").asString("").isBlank()
                && response.path("status").isMissingNode()
                && response.path("paymentStatus").isMissingNode()) {
            return Optional.empty();
        }
        String status = firstOf(
                response.path("status").asString(null),
                response.path("paymentStatus").asString(null),
                response.path("frameStatus").asString(null));
        if (status == null) {
            return Optional.empty();
        }
        String reference = firstOf(response.path("reference").asString(null),
                response.path("merchantReference").asString(null));
        BigDecimal amount = amountOf(response);
        String receipt = firstOf(response.path("transactionId").asString(null),
                response.path("id").asString(null), providerRef);

        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ACCEPTED", "APPROVED", "SUCCESS", "SUCCEEDED", "PAID", "COMPLETED" ->
                    Optional.of(new Settlement(providerRef, reference, true, amount, "AOA",
                            receipt, null));
            case "REJECTED", "DECLINED", "FAILED", "CANCELLED", "CANCELED", "EXPIRED" ->
                    Optional.of(new Settlement(providerRef, reference, false, amount, "AOA",
                            null, status));
            // CREATED, PENDING, PROCESSING, and anything this list has not seen.
            default -> Optional.empty();
        };
    }

    /**
     * A callback from EMIS, which proves nothing on its own.
     *
     * <p>Unsigned, so the body is read only far enough to learn which frame it
     * concerns and the verdict comes from asking EMIS. Believing it would let
     * anyone who learned a frame id mark a payment paid.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String frameId = referenceIn(rawBody);
        if (frameId == null || frameId.isBlank()) {
            throw Signatures.reject("Multicaixa Express", "no frame id to check");
        }
        return poll(frameId);
    }

    /** The frame a callback is about. Read out rather than parsed: nothing here is trusted. */
    static String referenceIn(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        for (String key : new String[]{"\"id\"", "\"frameToken\"", "\"frameId\"", "\"token\""}) {
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

    // ---------------------------------------------------------------- plumbing

    private RestClient client() {
        return RestClient.create(endpoints.multicaixa());
    }

    /** EMIS's own words where it gave any. */
    private static String refusal(JsonNode response) {
        if (response != null) {
            String message = response.path("message").asString(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
            String code = response.path("code").asString(null);
            if (code != null && !code.isBlank()) {
                return "EMIS refused the payment (" + code + ")";
            }
        }
        return "EMIS would not open a payment frame";
    }

    private static BigDecimal amountOf(JsonNode response) {
        String raw = firstOf(response.path("amount").asString(null),
                response.path("totalAmount").asString(null));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstOf(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
