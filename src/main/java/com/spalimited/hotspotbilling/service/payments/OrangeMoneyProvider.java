package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.i18n.Country;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orange Money — the second-largest wallet network on the continent.
 *
 * <p>Senegal, Mali, Burkina Faso and Guinea are Orange Money countries the way
 * Kenya is an M-Pesa country, and Orange is the largest single wallet in Cote
 * d'Ivoire. All of them were reaching customers through Flutterwave, which
 * means an aggregator's margin stacked on top of the wallet's own fee for the
 * biggest wallet in the market. This talks to Orange directly.
 *
 * <h2>It is a hosted page, not a handset push</h2>
 *
 * <p>Worth saying plainly, because the OAuth-and-re-query shape of this class
 * looks like the MTN and Airtel ones and the customer experience is not the
 * same. Orange's generally available product is the Web Payment API: it returns
 * a URL, the customer opens it, and they authorise there — in Cote d'Ivoire by
 * generating a code with {@code #144*82#} first. Orange does have direct-debit
 * APIs, but they are granted per-merchant rather than being something an
 * operator can sign up for, so building against them would ship a rail most
 * operators could not switch on.
 *
 * <h2>The sandbox cannot test a real amount</h2>
 *
 * <p>Orange's sandbox only accepts the fake currency {@code OUV} with an amount
 * of 1, whatever you ask for. So a successful sandbox payment proves the
 * credentials and the plumbing and proves nothing whatsoever about amounts or
 * currency conversion. That is Orange's design, not a shortcut here, and it is
 * why {@link #charge} substitutes both rather than letting Orange reject a real
 * figure with an error an operator would read as broken keys.
 *
 * <h2>Why the provider reference is three values glued together</h2>
 *
 * <p>Orange's status query needs the pay token, the order id <em>and</em> the
 * amount together — none of the three alone identifies a payment. This
 * interface carries one string, so all three live in it separated by pipes.
 * Ugly, and the alternative was three columns on the payments table used by one
 * rail out of thirteen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrangeMoneyProvider implements PaymentProvider {

    // Address moved to PaymentEndpoints; the default there is this URL.

    /** Separates the three values Orange needs to answer a status query. */
    static final String REF_SEPARATOR = "|";

    /**
     * Orange's sandbox takes this and only this. An amount of 1 "OUV" — not a
     * real currency — regardless of what was asked for.
     */
    private static final String SANDBOX_CURRENCY = "OUV";
    private static final String SANDBOX_SEGMENT = "dev";

    /**
     * The Orange Money markets this system also knows about.
     *
     * <p>Every Orange Money country this system also knows about. The payment
     * path segment is the lowercase ISO code, so a new market needs no string
     * anybody has to look up — which is why these could be added without
     * touching the request-building code at all.
     *
     * <p>Still not the full list: Orange Money also runs in Liberia,
     * Guinea-Bissau, the Central African Republic and across North Africa, and
     * those are absent because the country table does not name them yet.
     */
    private static final Set<Country> MARKETS = Set.of(
            Country.SN, Country.CI, Country.CM, Country.CD,
            Country.ML, Country.BF, Country.GN, Country.SL, Country.NE,
            Country.MG, Country.BW);

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PublicUrls urls;
    private final PaymentEndpoints endpoints;

    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.ORANGE_MONEY;
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
        return MarketGuard.currencyAgrees("Orange Money", country(),
                portalSettings.settings().getCurrencyCode());
    }

    @Override
    public boolean pollable() {
        return true;
    }

    private record Config(String clientId, String clientSecret, String merchantKey,
                          Country country, String currency, boolean live) {

        /** The country segment in the payment path — "dev" in the sandbox. */
        String segment() {
            return live ? country.name().toLowerCase() : SANDBOX_SEGMENT;
        }
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.ORANGE_MONEY)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getConsumerKey()) || blank(g.getConsumerSecret())
                || blank(g.getShortCode())) {
            return null;
        }
        Country country = country();
        if (!MARKETS.contains(country)) {
            return null;
        }
        boolean live = g.getEnvironment() == PaymentGateway.Environment.PRODUCTION;
        return new Config(g.getConsumerKey().trim(), g.getConsumerSecret().trim(),
                g.getShortCode().trim(), country,
                live ? country.currency() : SANDBOX_CURRENCY, live);
    }

    private Country country() {
        try {
            return Country.of(portalSettings.settings().getCountry());
        } catch (Exception e) {
            return Country.SN;
        }
    }

    /** True where Orange Money is worth offering at all. */
    public boolean availableHere() {
        return MARKETS.contains(country());
    }

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = config();
        if (cfg == null || !currencyAgrees()) {
            throw new IllegalStateException("Orange Money is not set up for this country");
        }
        String base = urls.origin();
        if (base == null) {
            // Orange refuses a payment with no notification URL, and a payment
            // that cannot notify is a payment that never completes. Better to
            // say so than to start one.
            throw new IllegalStateException("Orange Money needs this server's public address. "
                    + "Set the callback URL in settings first.");
        }

        // Whole units in the currency. XOF and XAF have no minor unit at all,
        // so a hundred-multiplier here would charge every customer a hundred
        // times the price.
        long amount = cfg.live()
                ? request.amount().setScale(0, RoundingMode.HALF_UP).longValueExact()
                : 1L;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchant_key", cfg.merchantKey());
        body.put("currency", cfg.currency());
        body.put("order_id", request.reference());
        body.put("amount", amount);
        body.put("return_url", base + "/?paid=" + enc(request.reference()));
        body.put("cancel_url", base + "/?cancelled=" + enc(request.reference()));
        body.put("notif_url", base + "/api/payments/orange-money/webhook");
        body.put("lang", cfg.country().language());
        body.put("reference", trim(request.description(), 30));

        JsonNode response;
        try {
            response = client().post()
                    .uri("/orange-money-webpay/{segment}/v1/webpayment", cfg.segment())
                    .header("Authorization", "Bearer " + token(cfg))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Orange Money web payment failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the Orange Money payment. "
                    + "Please try again.");
        }

        String payToken = response == null ? null : response.path("pay_token").asString(null);
        String paymentUrl = response == null ? null : response.path("payment_url").asString(null);
        if (payToken == null || paymentUrl == null) {
            throw new IllegalStateException("Orange refused the payment: "
                    + (response == null ? "no response" : response.path("message").asString("refused")));
        }
        return new Charge(encodeRef(payToken, request.reference(), amount), paymentUrl);
    }

    /**
     * Asks Orange how a payment ended.
     *
     * <p>The only trustworthy verdict for this rail. Orange's notification
     * carries a {@code notif_token} that was handed out when the payment
     * started, which is a real check but requires having stored it; asking
     * Orange is both simpler and stronger, since the answer comes from Orange
     * rather than from whoever posted the body.
     */
    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        Ref ref = decodeRef(providerRef);
        if (cfg == null || ref == null) {
            return Optional.empty();
        }
        JsonNode response;
        try {
            response = client().post()
                    .uri("/orange-money-webpay/{segment}/v1/transactionstatus", cfg.segment())
                    .header("Authorization", "Bearer " + token(cfg))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("order_id", ref.orderId(),
                            "amount", ref.amount(),
                            "pay_token", ref.payToken()))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Orange Money status for {} failed: {}", ref.orderId(), e.getMessage());
            return Optional.empty();
        }
        return read(response, providerRef, ref.orderId());
    }

    /**
     * Orange's status document, turned into a verdict.
     *
     * <p>{@code INITIATED} and {@code PENDING} both mean the customer has not
     * finished, and calling either a failure cancels a live sale. {@code
     * EXPIRED} is the one ambiguous-looking state that really is over.
     */
    static Optional<Settlement> read(JsonNode response, String providerRef, String orderId) {
        if (response == null) {
            return Optional.empty();
        }
        String status = response.path("status").asString("").toUpperCase();
        String txnId = response.path("txnid").asString(null);
        return switch (status) {
            case "SUCCESS", "SUCCESSFULL", "SUCCESSFUL" -> Optional.of(new Settlement(
                    providerRef, orderId, true, null, null, txnId, null));
            case "FAILED" -> Optional.of(new Settlement(
                    providerRef, orderId, false, null, null, null, "declined"));
            case "EXPIRED" -> Optional.of(new Settlement(
                    providerRef, orderId, false, null, null, null,
                    "the payment page expired before it was paid"));
            default -> Optional.empty();
        };
    }

    /**
     * The order id a notification is about, or null if there isn't one.
     *
     * <p>All the notification is used for. Orange sends {@code status}, {@code
     * order_id}, {@code amount} and the {@code notif_token} it issued when the
     * payment started, and none of it is signed with a secret only Orange and
     * this server share — so nothing in the body decides anything. Believing it
     * would let anyone who guessed an order id, which is a sequence number, mark
     * a payment paid.
     */
    public static String notifiedOrderId(byte[] rawBody) {
        return fieldIn(rawBody, "order_id");
    }

    /**
     * Orange's notification cannot settle a payment by itself.
     *
     * <p>The status query needs the pay token, and Orange does not put it in the
     * callback. It also cannot be put in the {@code notif_url}, because Orange
     * only issues the token in its reply to the very request that carries that
     * URL. So the stored reference has to be looked up, which needs the
     * repository — and that belongs in PaymentService, not in a provider. The
     * webhook endpoint does the lookup and then calls {@link #poll}.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        throw new UnsupportedOperationException(
                "Orange Money settles by lookup then poll; see ProviderWebhookController");
    }

    // --- the three-part reference ---

    record Ref(String payToken, String orderId, long amount) {
    }

    /**
     * Order id first, then amount, then the pay token.
     *
     * <p>The order matters. A notification from Orange quotes the order id and
     * nothing else useful, so the stored reference has to be findable by a
     * prefix search on it — see {@code refStartingWith} in PaymentService. The
     * pay token goes last because it is the only one of the three that is opaque
     * and could itself contain the separator; taking it as "everything after the
     * second pipe" is safe whatever Orange puts in it.
     */
    static String encodeRef(String payToken, String orderId, long amount) {
        return orderId + REF_SEPARATOR + amount + REF_SEPARATOR + payToken;
    }

    /** The prefix a notification can search on, given only the order id. */
    public static String refPrefix(String orderId) {
        return orderId + REF_SEPARATOR;
    }

    /** Null for anything that is not one of ours, including a bare pay token. */
    static Ref decodeRef(String providerRef) {
        if (providerRef == null || providerRef.isBlank()) {
            return null;
        }
        int firstPipe = providerRef.indexOf(REF_SEPARATOR);
        if (firstPipe < 0) {
            return null;
        }
        int secondPipe = providerRef.indexOf(REF_SEPARATOR, firstPipe + 1);
        if (secondPipe < 0) {
            return null;
        }
        try {
            return new Ref(providerRef.substring(secondPipe + 1),
                    providerRef.substring(0, firstPipe),
                    Long.parseLong(providerRef.substring(firstPipe + 1, secondPipe)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One string field out of a JSON body, without trusting it enough to model it. */
    static String fieldIn(byte[] rawBody, String field) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        int at = body.indexOf("\"" + field + "\"");
        if (at < 0) {
            return null;
        }
        int colon = body.indexOf(':', at);
        int quote = body.indexOf('"', colon);
        int comma = body.indexOf(',', colon);
        int brace = body.indexOf('}', colon);
        if (colon < 0) {
            return null;
        }
        // Quoted where it is a string, bare where Orange sends it as a number.
        if (quote > 0 && (comma < 0 || quote < comma) && (brace < 0 || quote < brace)) {
            int end = body.indexOf('"', quote + 1);
            return end > quote ? body.substring(quote + 1, end) : null;
        }
        int end = comma > 0 && (brace < 0 || comma < brace) ? comma : brace;
        return end > colon ? body.substring(colon + 1, end).trim() : null;
    }

    // --- plumbing ---

    private RestClient client() {
        return RestClient.create(endpoints.orange());
    }

    /**
     * Orange's token endpoint takes HTTP Basic authentication and a
     * form-encoded body, not the JSON that MTN and Airtel take. A JSON body
     * here comes back as an unhelpful 400.
     */
    private String token(Config cfg) {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString(
                (cfg.clientId() + ":" + cfg.clientSecret()).getBytes(StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        JsonNode response = client().post()
                .uri("/oauth/v3/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("access_token").asString(null) == null) {
            throw new IllegalStateException("Orange would not issue a token — check the client id "
                    + "and secret, and that the app is subscribed to Orange Money Web Payment");
        }
        token = response.path("access_token").asString(null);
        long seconds = response.path("expires_in").asLong(3600);
        tokenExpiresAt = Instant.now().plus(Duration.ofSeconds(Math.max(60, seconds - 60)));
        return token;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
