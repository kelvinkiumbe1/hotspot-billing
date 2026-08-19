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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Paymob — Egypt.
 *
 * <p>The largest gap on the continent by some distance. Egypt has more people
 * than any country this system reaches and nothing here could take a pound from
 * any of them. Paymob is the aggregator that covers what Egyptians actually pay
 * with: Vodafone Cash and the other telco wallets, InstaPay, Meeza cards and
 * ordinary cards, behind one hosted page.
 *
 * <h2>Three things to get wrong</h2>
 *
 * <p><b>A charge is three calls, not one.</b> An auth token, then an order, then
 * a payment key, and only then is there a page to send the customer to. Each one
 * needs the output of the last, so a failure halfway leaves an order with no
 * payment against it — harmless, and worth knowing when reading their dashboard.
 *
 * <p><b>The webhook signature is not over the body.</b> Paymob concatenates
 * twenty named fields in a fixed order and signs that. Signing the raw bytes —
 * which is what every other rail here does, and what any reasonable person would
 * try first — fails every single time, and the failure looks like an attack
 * rather than a mistake. See {@link #signature}.
 *
 * <p><b>Amounts are piastres.</b> {@code amount_cents} is minor units, so a
 * hundred pounds is 10000. This is the opposite of the mobile-money rails next
 * door, and it is the direction that overcharges rather than undercharges.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymobProvider implements PaymentProvider {

    /** Egypt. Paymob has opened elsewhere but this is what the table covers. */
    private static final Set<Country> MARKETS = Set.of(Country.EG);

    /**
     * The fields Paymob signs, in the order it concatenates them.
     *
     * <p>This list <em>is</em> the signature. Not the body, not a subset of it,
     * not alphabetical by luck — this exact sequence, values joined with nothing
     * between them. A field in the wrong place produces a hash that is wrong in a
     * way indistinguishable from a forgery, so every webhook would be rejected
     * and no voucher would ever be issued.
     *
     * <p>{@code error_occured} is spelled the way Paymob spells it. Correcting it
     * to {@code error_occurred} reads as a typo fix and breaks every signature.
     */
    private static final String[] SIGNED_FIELDS = {
            "amount_cents", "created_at", "currency", "error_occured",
            "has_parent_transaction", "id", "integration_id", "is_3d_secure",
            "is_auth", "is_capture", "is_refunded", "is_standalone_payment",
            "is_voided", "order.id", "owner", "pending",
            "source_data.pan", "source_data.sub_type", "source_data.type", "success",
    };

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final ObjectMapper mapper;
    private final PaymentEndpoints endpoints;

    /** Auth tokens last an hour; one per charge would be three calls of waste. */
    private volatile String authToken;
    private volatile Instant authExpiresAt = Instant.EPOCH;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.PAYMOB;
    }

    @Override
    public boolean usable() {
        return availableHere() && currencyAgrees() && config() != null;
    }

    /** True where Paymob is worth offering at all. */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("Paymob", MARKETS,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Prices have to be in pounds, because that is what Paymob collects.
     *
     * <p>Same guard the mobile-money rails use, for the same reason: the country
     * and the currency are two separate settings and can disagree. A plan priced
     * 1000 and shown as KES 1,000 would go to Paymob as 1000 EGP.
     */
    private boolean currencyAgrees() {
        try {
            return MarketGuard.currencyAgrees("Paymob", Country.EG,
                    portalSettings.settings().getCurrencyCode());
        } catch (Exception e) {
            return false;
        }
    }

    private record Config(String apiKey, String hmacSecret, String integrationId,
                          String iframeId) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.PAYMOB)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getSecretKey()) || blank(g.getWebhookSecret())
                || blank(g.getShortCode()) || blank(g.getPublicKey())) {
            return null;
        }
        return new Config(g.getSecretKey().trim(), g.getWebhookSecret().trim(),
                g.getShortCode().trim(), g.getPublicKey().trim());
    }

    // ------------------------------------------------------------------ charge

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = availableHere() && currencyAgrees() ? config() : null;
        if (cfg == null) {
            throw new IllegalStateException("Paymob is not set up for this country");
        }
        String token = token(cfg);
        long piastres = piastres(request.amount());

        // 1. An order. merchant_order_id is ours and has to be unique -- Paymob
        //    refuses a repeat, which is what makes it safe to match on.
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("auth_token", token);
        order.put("delivery_needed", false);
        order.put("amount_cents", piastres);
        order.put("currency", "EGP");
        order.put("merchant_order_id", request.reference());
        order.put("items", java.util.List.of());

        JsonNode created = post(cfg, "/ecommerce/orders", order, "create an order");
        String orderId = created.path("id").asString(null);
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalStateException(refusal(created, "Paymob would not open an order"));
        }

        // 2. A payment key for that order.
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("auth_token", token);
        key.put("amount_cents", piastres);
        key.put("expiration", 3600);
        key.put("order_id", orderId);
        key.put("billing_data", billing(request));
        key.put("currency", "EGP");
        key.put("integration_id", cfg.integrationId());

        JsonNode issued = post(cfg, "/acceptance/payment_keys", key, "issue a payment key");
        String paymentToken = issued.path("token").asString(null);
        if (paymentToken == null || paymentToken.isBlank()) {
            throw new IllegalStateException(refusal(issued, "Paymob would not issue a payment key"));
        }

        // 3. The page. Paymob's own iframe, so cards and wallets are its problem
        //    rather than ours and no card number ever touches this server.
        String url = endpoints.paymob() + "/acceptance/iframes/" + cfg.iframeId()
                + "?payment_token=" + URLEncoder.encode(paymentToken, StandardCharsets.UTF_8);
        // The order id, because that is what order.id in the webhook carries.
        return new Charge(orderId, url);
    }

    /**
     * Billing details, every field filled.
     *
     * <p>Paymob rejects the payment key outright if any of these is missing, and
     * a hotspot customer has given us a phone number and nothing else. "NA" is
     * what Paymob's own documentation uses for the fields that do not apply, so
     * the sale goes through rather than being refused over a house number.
     */
    private static Map<String, Object> billing(ChargeRequest request) {
        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("first_name", "WiFi");
        billing.put("last_name", "Customer");
        billing.put("email", PaymentEmails.forCustomer(request.email(), request.phoneNumber()));
        billing.put("phone_number", request.phoneNumber() == null ? "NA" : request.phoneNumber());
        for (String unused : new String[]{"apartment", "floor", "street", "building",
                "shipping_method", "postal_code", "city", "state", "country"}) {
            billing.put(unused, "NA");
        }
        return billing;
    }

    /**
     * Pounds to piastres.
     *
     * <p>Minor units, unlike every mobile-money rail in this package. Sending
     * major units here undercharges by a hundred; the mistake in the other
     * direction is what this comment exists to stop somebody "fixing".
     */
    static long piastres(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    // ----------------------------------------------------------------- webhook

    /**
     * A Paymob callback, verified the way Paymob signs it.
     *
     * <p>The signature covers {@link #SIGNED_FIELDS} concatenated, not the body,
     * so this cannot use the raw bytes the way the other rails do. That is the
     * whole reason this method is longer than its neighbours.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        Config cfg = config();
        if (cfg == null) {
            throw Signatures.reject("Paymob", "not configured");
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw Signatures.reject("Paymob", "the body was not readable");
        }
        JsonNode obj = root.path("obj").isMissingNode() ? root : root.path("obj");

        String given = firstOf(root.path("hmac").asString(null),
                obj.path("hmac").asString(null),
                Signatures.header(headers, "hmac"));
        if (given == null || given.isBlank()) {
            throw Signatures.reject("Paymob", "no signature on the callback");
        }
        String expected = signature(obj, cfg.hmacSecret());
        if (!Signatures.matches(expected, given)) {
            throw Signatures.reject("Paymob", "signature did not match");
        }

        // Only transactions settle anything. Paymob sends several kinds of event
        // down one endpoint and a token callback is not a payment.
        String type = root.path("type").asString("TRANSACTION");
        if (!"TRANSACTION".equalsIgnoreCase(type)) {
            return Optional.empty();
        }
        // A payment still being processed is not a failure. Reporting one would
        // cancel a sale from a customer mid-3DS.
        if (obj.path("pending").asBoolean(false)) {
            return Optional.empty();
        }
        boolean paid = obj.path("success").asBoolean(false);
        // A refund or a void arrives with success true and a flag beside it.
        // Reading success alone would issue a voucher for money going back out.
        if (paid && (obj.path("is_refunded").asBoolean(false)
                || obj.path("is_voided").asBoolean(false))) {
            return Optional.empty();
        }
        String orderId = obj.path("order").path("id").asString(null);
        String reference = obj.path("order").path("merchant_order_id").asString(null);
        BigDecimal amount = pounds(obj.path("amount_cents").asString(null));

        return Optional.of(new Settlement(orderId, reference, paid, amount, "EGP",
                paid ? obj.path("id").asString(orderId) : null,
                paid ? null : reason(obj)));
    }

    /**
     * The signature Paymob would have produced for this transaction.
     *
     * <p>Package-private so it can be checked against a payload and a hash taken
     * from Paymob's own documentation, which is the only way to know this is
     * right without a live merchant account.
     */
    static String signature(JsonNode obj, String hmacSecret) {
        StringBuilder joined = new StringBuilder();
        for (String field : SIGNED_FIELDS) {
            JsonNode node = obj;
            for (String part : field.split("\\.")) {
                node = node.path(part);
            }
            joined.append(flat(node));
        }
        return Signatures.hmacHex("HmacSHA512", hmacSecret,
                joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One field, as it goes into the signature.
     *
     * <p>Booleans are the trap: they have to be the lowercase JSON spelling, not
     * "True" and not "1". An absent or null field contributes nothing at all
     * rather than the word "null".
     */
    private static String flat(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "true" : "false";
        }
        return node.asString("");
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Nothing to poll.
     *
     * <p>Paymob's webhook is what settles a payment, the same as the other hosted
     * checkouts here. It has an inquiry endpoint, and it is deliberately not used
     * yet: a status reader written against documentation and never run is how a
     * good payment gets marked failed, and the sweep timing a payment out is the
     * honest alternative to inventing a verdict.
     */
    @Override
    public boolean pollable() {
        return false;
    }

    private RestClient client() {
        return RestClient.create(endpoints.paymob());
    }

    private JsonNode post(Config cfg, String path, Map<String, Object> body, String what) {
        try {
            JsonNode response = client().post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // Paymob puts the reason in the body on a 4xx. Letting the
                    // status throw discards it.
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
            if (response == null) {
                throw new IllegalStateException("Paymob sent nothing back");
            }
            return response;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Paymob could not {}: {}", what, e.getMessage());
            throw new IllegalStateException("Could not reach Paymob. Please try again.");
        }
    }

    /**
     * An auth token, cached until shortly before it expires.
     *
     * <p>The API key buys this and is used nowhere else. Every other call carries
     * the token in the body — not a header, which is where anybody who has used
     * another gateway will look for it.
     */
    private String token(Config cfg) {
        if (authToken != null && Instant.now().isBefore(authExpiresAt)) {
            return authToken;
        }
        JsonNode response = post(cfg, "/auth/tokens",
                Map.of("api_key", cfg.apiKey()), "authenticate");
        String token = response.path("token").asString(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(refusal(response,
                    "Paymob would not accept the API key"));
        }
        authToken = token;
        // Paymob says an hour. Ten minutes short, because a charge is three
        // calls and a token expiring between them fails the middle one.
        authExpiresAt = Instant.now().plus(Duration.ofMinutes(50));
        return authToken;
    }

    /** Paymob's own words where it gave any. */
    private static String refusal(JsonNode response, String fallback) {
        if (response != null) {
            for (String field : new String[]{"detail", "message", "error"}) {
                String value = response.path(field).asString(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private static String reason(JsonNode obj) {
        String message = obj.path("data").path("message").asString(null);
        if (message != null && !message.isBlank()) {
            return message;
        }
        String txnMessage = obj.path("data").path("txn_response_code").asString(null);
        return txnMessage != null && !txnMessage.isBlank() ? txnMessage : "declined";
    }

    private static BigDecimal pounds(String amountCents) {
        if (amountCents == null || amountCents.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(amountCents).movePointLeft(2);
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
