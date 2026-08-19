package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.i18n.Country;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import javax.crypto.Cipher;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Vodacom M-Pesa — Tanzania, Mozambique and the DRC.
 *
 * <p>The name is the same as Kenya's and almost nothing else is. Safaricom's
 * Daraja and Vodacom's OpenAPI are separate products on separate platforms with
 * separate credentials, and no part of {@link MpesaProvider} applies here.
 * Tanzania is the reason this exists: M-Pesa is the largest wallet in the
 * country and until now a Tanzanian operator could only reach it through
 * Flutterwave — an aggregator margin on top of the wallet's own fee, to reach a
 * wallet that was there all along.
 *
 * <h2>What is genuinely different, and dangerous</h2>
 *
 * <p><b>Nothing calls back.</b> There is no webhook. The only way to learn how a
 * payment ended is to ask, so {@link #poll} is not a safety net here the way it
 * is for MTN — it is the whole settlement path, and the reconcile sweep is what
 * issues every voucher this rail sells.
 *
 * <p><b>Authentication is two RSA encryptions, not a password.</b> The API key
 * is encrypted under Vodacom's public key to fetch a session; the session id is
 * then encrypted under the same key to become the bearer for everything else.
 * Get the padding wrong and every call fails with an error about credentials
 * that says nothing about padding.
 *
 * <p><b>The charge call blocks while the customer types their PIN.</b> Unlike
 * every other rail here, the HTTP response is the outcome rather than an
 * acknowledgement. That makes a timeout ambiguous in the worst possible way:
 * the customer may well have paid. So a timeout is deliberately not a failure —
 * see {@link #charge}.
 *
 * <p><b>The MSISDN is international.</b> {@code 255744553344}, not
 * {@code 0744553344} and not {@code +255...}. Airtel wants the exact opposite
 * and the two classes sit next to each other; the wrong one is a charge that is
 * accepted and then quietly finds nobody.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VodacomMpesaProvider implements PaymentProvider {

    /** A market, as it appears in the URL and again in the request body. */
    private record Market(String path, String code) {
    }

    /**
     * Where Vodacom M-Pesa runs on this platform.
     *
     * <p>Lesotho is the fourth and is absent only because the country table has
     * no entry for it yet; adding {@code LS} there and a line here is all it
     * would take. Vodafone Ghana and Egypt sit on the same API and are left out
     * deliberately — Ghana's M-Pesa became Telecel and MTN MoMo is the rail that
     * matters there, and Egypt is not somewhere this product sells.
     */
    private static final Map<Country, Market> MARKETS = Map.of(
            Country.TZ, new Market("vodacomTZN", "TZN"),
            Country.MZ, new Market("vodacomMOZ", "MOZ"),
            Country.CD, new Market("vodacomDRC", "DRC"));

    private static final Set<Country> COUNTRIES = MARKETS.keySet();

    /**
     * Long enough for a customer to find their phone and type a PIN, short
     * enough that the portal is not left hanging on one request.
     *
     * <p>Cutting the call off does not cancel the payment — Vodacom has it
     * either way — which is precisely why this can be bounded at all.
     */
    private static final Duration PIN_WAIT = Duration.ofSeconds(35);

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PaymentEndpoints endpoints;

    /** Sessions last about an hour; one per charge would be slow and rude. */
    private volatile String sessionBearer;
    private volatile Instant sessionExpiresAt = Instant.EPOCH;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.VODACOM_MPESA;
    }

    @Override
    public boolean usable() {
        return availableHere() && config() != null;
    }

    /**
     * Asking is the only way this rail ever settles.
     *
     * <p>Not a fallback. With no webhook, every voucher this sells is issued by
     * the reconcile sweep coming back and asking.
     */
    @Override
    public boolean pollable() {
        return true;
    }

    /**
     * True where Vodacom M-Pesa is worth offering at all.
     *
     * <p>Deliberately not consulted when polling, for the same reason the other
     * gated rails do not consult theirs when settling: a payment already begun
     * has to be finishable, and here the poll <em>is</em> the settlement.
     */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("Vodacom M-Pesa", COUNTRIES,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    private record Config(String baseUrl, String apiKey, PublicKey publicKey,
                          String serviceProviderCode, Market market, String currency) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.VODACOM_MPESA)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getSecretKey()) || blank(g.getPublicKey())
                || blank(g.getShortCode())) {
            return null;
        }
        Market market = MARKETS.get(country());
        if (market == null) {
            return null;
        }
        PublicKey key;
        try {
            key = publicKey(g.getPublicKey());
        } catch (Exception e) {
            // A mis-pasted key is the likeliest setup mistake by some distance,
            // and every call it makes fails with an authentication error that
            // blames the credentials rather than their format.
            log.warn("Vodacom M-Pesa public key could not be read: {}", e.getMessage());
            return null;
        }
        boolean live = g.getEnvironment() == PaymentGateway.Environment.PRODUCTION;
        return new Config(endpoints.vodacom(live, market.path()),
                g.getSecretKey().trim(), key, g.getShortCode().trim(),
                market, country().currency());
    }

    private Country country() {
        try {
            return Country.of(portalSettings.settings().getCountry());
        } catch (Exception e) {
            return Country.TZ;
        }
    }

    /**
     * Starts — and usually finishes — a payment.
     *
     * <p>The response to this call is the outcome, not an acknowledgement, which
     * makes every way it can go wrong worth naming.
     *
     * <p>A refusal Vodacom is certain about — no such subscriber, not enough
     * money, the customer pressed cancel — throws, because the customer is
     * standing there and deserves to be told rather than left watching a
     * spinner.
     *
     * <p>Everything else does not throw, and this is the money-critical part.
     * A timeout, a dropped connection or a "try again shortly" all mean the
     * charge may have reached Vodacom and may already have been paid. Throwing
     * marks the payment failed — {@code PaymentService.start} does that with the
     * exception — so a customer whose money had left their wallet would be told
     * it failed and given nothing. Returning normally hands the reference to the
     * reconcile sweep, which asks Vodacom what actually happened.
     */
    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = availableHere() ? config() : null;
        if (cfg == null) {
            throw new IllegalStateException("Vodacom M-Pesa is not set up for this country");
        }
        // Ours, and settled on before the call, because it is what every later
        // question about this payment is keyed on. A charge whose response never
        // arrives still has to be findable.
        String reference = reference(request.reference());

        Map<String, Object> body = new LinkedHashMap<>();
        // Major units. Vodacom reads "2000" as two thousand shillings; the
        // hundredfold a card processor expects would be a fortune.
        body.put("input_Amount", amount(request.amount()));
        body.put("input_Country", cfg.market().code());
        body.put("input_Currency", cfg.currency());
        body.put("input_CustomerMSISDN", msisdn(request.phoneNumber()));
        body.put("input_ServiceProviderCode", cfg.serviceProviderCode());
        body.put("input_ThirdPartyConversationID", conversationId());
        body.put("input_TransactionReference", reference);
        body.put("input_PurchasedItemsDesc", trim(request.description(), 256));

        // The session is fetched before the try: failing to open one means
        // nothing was sent, which is a different thing from a charge that may
        // have landed, and the customer can be told plainly.
        String bearer = session(cfg);

        JsonNode response;
        try {
            response = client(cfg, PIN_WAIT).post()
                    .uri("/c2bPayment/singleStage/")
                    .header("Authorization", "Bearer " + bearer)
                    .header("Origin", "*")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            // Ambiguous, and treated as such on purpose. See the javadoc.
            log.warn("Vodacom M-Pesa charge {} gave no answer ({}); leaving it for the sweep to ask",
                    reference, e.getMessage());
            return new Charge(reference, null);
        }

        String code = code(response);
        if (code.isEmpty()) {
            // Not Vodacom answering -- a proxy, a captive portal, an error page.
            // Loud, because the sweep will now poll a reference that may not
            // exist, but still not a failure: guessing costs a paying customer.
            log.warn("Vodacom M-Pesa answered charge {} with nothing recognisable; "
                    + "the sweep will ask about it", reference);
        }
        if (refused(code)) {
            throw new IllegalStateException(describe(response, code));
        }
        // Anything not a definite refusal — accepted, still running, duplicate,
        // Vodacom overloaded — goes to the sweep rather than to the customer.
        // No checkout URL: they are already looking at a PIN prompt.
        return new Charge(reference, null);
    }

    /**
     * What Vodacom says about a charge. The only thing that ever settles this
     * rail, since nothing calls back.
     */
    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode status;
        try {
            status = client(cfg, Duration.ofSeconds(20)).get()
                    .uri(uri -> uri.path("/queryTransactionStatus/")
                            .queryParam("input_QueryReference", providerRef)
                            .queryParam("input_ServiceProviderCode", cfg.serviceProviderCode())
                            .queryParam("input_ThirdPartyConversationID", conversationId())
                            .queryParam("input_Country", cfg.market().code())
                            .build())
                    .header("Authorization", "Bearer " + session(cfg))
                    .header("Origin", "*")
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Vodacom M-Pesa status for {} unavailable: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(status, providerRef, cfg.currency());
    }

    /**
     * A status document, turned into a verdict.
     *
     * <p>Package-private so the states can be pinned without a socket. The one
     * that matters most is the one that is not here: a transaction Vodacom
     * still calls pending returns empty. Reporting it as unpaid would cancel a
     * sale from a customer who is mid-PIN, and this rail has no webhook to
     * correct it afterwards.
     */
    static Optional<Settlement> read(JsonNode status, String providerRef, String currency) {
        if (status == null) {
            return Optional.empty();
        }
        // INS-0 here means the question was answered, not that anything was
        // paid. The transaction's own state is a separate field, and reading
        // the envelope instead would call every answered query a success.
        if (!"INS-0".equals(code(status))) {
            return Optional.empty();
        }
        String state = status.path("output_ResponseTransactionStatus").asString("");
        String receipt = status.path("output_TransactionID").asString(providerRef);
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "completed", "success", "successful" -> Optional.of(new Settlement(
                    providerRef, providerRef, true, null, currency, receipt, null));
            case "failed", "cancelled", "canceled", "declined", "expired", "rejected" ->
                    Optional.of(new Settlement(providerRef, providerRef, false, null, currency,
                            null, status.path("output_ResponseDesc").asString("declined")));
            default -> Optional.empty();
        };
    }

    /**
     * There is no webhook, so nothing may arrive claiming to be one.
     *
     * <p>No route posts here today. It refuses rather than returning empty
     * because an endpoint that accepted an unsigned body naming a reference
     * would be a free-internet generator, and "we do not have one of those" is
     * the safest thing this can grow into.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        throw Signatures.reject("Vodacom M-Pesa",
                "this rail has no webhook; it is settled by asking");
    }

    // --- reference, phone and response shapes ---

    /**
     * Our reference, in the alphabet Vodacom accepts.
     *
     * <p>Alphanumeric, twenty characters. Ours are {@code HS-31} and
     * {@code PPPOE-4-88}, so something has to give — and simply deleting the
     * punctuation is the trap: {@code PPPOE-4-88} and {@code PPPOE-48-8} are two
     * different payments that both become {@code PPPOE488}. One would be refused
     * as a duplicate, or worse, a query about one would answer about the other.
     * So a separator becomes a letter rather than nothing, and a literal one in
     * the source is doubled so the mapping stays reversible.
     *
     * <p>An over-long reference keeps its tail, which is where the payment id
     * lives and therefore where the uniqueness is.
     */
    static String reference(String ours) {
        if (ours == null || ours.isBlank()) {
            return conversationId().substring(0, 20);
        }
        StringBuilder out = new StringBuilder();
        for (char c : ours.toCharArray()) {
            if (c == 'Z') {
                out.append("ZZ");
            } else if ((c >= 'A' && c <= 'Y') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else {
                out.append('Z');
            }
        }
        String s = out.toString();
        return s.length() <= 20 ? s : s.substring(s.length() - 20);
    }

    /**
     * The number in the form Vodacom identifies a subscriber by.
     *
     * <p>International, digits only, no plus. Airtel wants the national number
     * with the country code stripped, and given the wrong one Vodacom accepts
     * the request and then finds nobody — which reads as a prompt that was sent
     * and never arrived.
     */
    static String msisdn(String phoneNumber) {
        return phoneNumber == null ? null : phoneNumber.replaceAll("\\D", "");
    }

    /** A fresh correlation id. Vodacom rejects a repeat, so never reused. */
    private static String conversationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String code(JsonNode node) {
        return node == null ? "" : node.path("output_ResponseCode").asString("");
    }

    /**
     * Codes that mean the payment is definitely not happening.
     *
     * <p>The list is short on purpose. Everything absent from it — including
     * {@code INS-9} (no answer from the handset yet), {@code INS-10} (we have
     * already sent this one) and {@code INS-16} (Vodacom is busy) — is a payment
     * whose fate is not yet known, and calling any of those a failure risks
     * telling a customer who paid that they did not.
     */
    private static boolean refused(String code) {
        return switch (code) {
            case "INS-5",     // the customer pressed cancel
                 "INS-6",     // the transaction itself failed
                 "INS-13",    // no such shortcode
                 "INS-15",    // the amount is not acceptable
                 "INS-17",    // the reference is malformed
                 "INS-2001",  // the credentials were refused
                 "INS-2006",  // not enough money in the wallet
                 "INS-2051",  // no such subscriber
                 "INS-2057" -> true;
            // INS-997 is "API Not Enabled": the credentials are right, the
            // session opens, and the C2B product has not been switched on for
            // the app. Seen for real against the sandbox, and it was in the
            // ambiguous bucket -- so a customer pressed Pay, nothing was ever
            // sent to their phone, and the sweep waited a quarter of an hour
            // before failing it. Nothing about it can resolve itself.
            case "INS-997" -> true;
            default -> code.startsWith("INS-99");
        };
    }

    /**
     * Whether this rail can take a payment, asked of Vodacom rather than of the
     * saved settings.
     *
     * <p>Everything {@link #usable} can see is whether three fields are filled
     * in. Two things it cannot see will each stop every payment: a key the
     * market does not recognise, and an app whose C2B product has never been
     * switched on. The second is the one that hurts, because the credentials are
     * genuinely correct and the session genuinely opens -- so the admin looks
     * healthy and every customer times out.
     *
     * <p>Moves no money. It opens a session and then asks after a reference that
     * cannot exist: a live API says it has never heard of it, a dormant one says
     * INS-997 whatever you ask.
     */
    public String verify() {
        Config cfg = config();
        if (cfg == null) {
            throw new IllegalStateException(availableHere()
                    ? "Fill in the API key, the public key and the service provider code first"
                    : "Your country is not one Vodacom M-Pesa serves — it covers "
                      + "Tanzania, Mozambique and DR Congo");
        }
        // Throws with Vodacom's own words if the credentials are wrong.
        String bearer = session(cfg);

        JsonNode answer;
        try {
            answer = client(cfg, Duration.ofSeconds(20)).get()
                    .uri(uri -> uri.path("/queryTransactionStatus/")
                            .queryParam("input_QueryReference", "ZIDICHECK" + conversationId().substring(0, 8))
                            .queryParam("input_ServiceProviderCode", cfg.serviceProviderCode())
                            .queryParam("input_ThirdPartyConversationID", conversationId())
                            .queryParam("input_Country", cfg.market().code())
                            .build())
                    .header("Authorization", "Bearer " + bearer)
                    .header("Origin", "*")
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("Vodacom accepted the credentials but would not answer a "
                    + "status query. Try again shortly.");
        }
        String code = code(answer);
        if ("INS-997".equals(code)) {
            throw new IllegalStateException("Vodacom accepted the credentials, but the payment API is "
                    + "not switched on for this app (INS-997). On the M-Pesa OpenAPI portal, open your "
                    + "app and enable the Customer to Business Single Stage and Query Transaction "
                    + "Status products for " + cfg.market().path() + ".");
        }
        // Anything else means the API answered a real question -- including
        // telling us the invented reference does not exist, which is the answer
        // that proves it is working.
        return "Vodacom accepted these credentials and the payment API is live for "
                + cfg.market().path() + ".";
    }

    /**
     * Vodacom's own words where it has any, and the code where it does not.
     *
     * <p>Two shapes, because Vodacom uses two. A refusal from inside the payment
     * platform carries {@code output_ResponseCode} and
     * {@code output_ResponseDesc}, which is what the documentation describes. A
     * refusal at the gate -- a key the market does not recognise -- carries
     * neither, and says only {@code {"output_error":"API or Session key is not
     * authorized"}}.
     *
     * <p>Reading only the documented pair turned that one useful sentence into
     * "Vodacom M-Pesa refused the payment ()", which tells an operator with a
     * mistyped key nothing at all. Found by pointing this at the real sandbox.
     */
    private static String describe(JsonNode response, String code) {
        if (response != null) {
            for (String field : new String[]{"output_ResponseDesc", "output_error", "output_ErrorDesc"}) {
                String value = response.path(field).asString(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return code == null || code.isBlank()
                ? "Vodacom M-Pesa refused it without saying why"
                : "Vodacom M-Pesa refused the payment (" + code + ")";
    }

    /**
     * The amount, in the shape Vodacom's own SDK sample sends.
     *
     * <p>Their sample sends {@code "10"} rather than {@code "10.00"}, so a whole
     * amount goes without a decimal point -- byte-identical to the request they
     * publish, which is the only request anybody can point at and say it works.
     * Cents are kept where the amount actually has them, because the metical
     * does have subunits even though the shilling in practice does not.
     */
    static String amount(java.math.BigDecimal value) {
        java.math.BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        return rounded.stripTrailingZeros().scale() <= 0
                ? rounded.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : rounded.toPlainString();
    }

    // --- session and plumbing ---

    /**
     * The HTTP client, and specifically <em>not</em> the default one.
     *
     * <p>{@code SimpleClientHttpRequestFactory} runs on {@code HttpURLConnection},
     * which silently drops headers on the JDK's restricted list. {@code Origin}
     * is on that list, and Vodacom rejects every request that arrives without
     * it: "Origin header is missing". The header was set in the code the whole
     * time and never left the machine.
     *
     * <p>Nothing found this but the real API. A stand-in server does not care
     * which headers it is missing, and the two other places that could have
     * caught it -- a test asserting the header arrives -- did not exist until
     * this did.
     */
    private RestClient client(Config cfg, Duration readTimeout) {
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        // Bounded deliberately. The charge call holds the connection open while
        // the customer types, and without this the portal's own request would
        // wait as long as Vodacom cared to keep it.
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(cfg.baseUrl()).requestFactory(factory).build();
    }

    /**
     * The bearer token every call but one carries, cached until it expires.
     *
     * <p>Two encryptions rather than a login. The API key encrypted under
     * Vodacom's public key buys a session id; that session id encrypted under
     * the same key is the bearer. The session id itself is never sent as it
     * came, and a request carrying it plainly is refused with an error that does
     * not mention encryption.
     */
    private String session(Config cfg) {
        if (sessionBearer != null && Instant.now().isBefore(sessionExpiresAt)) {
            return sessionBearer;
        }
        JsonNode response;
        try {
            response = client(cfg, Duration.ofSeconds(20)).get()
                    .uri("/getSession/")
                    .header("Authorization", "Bearer " + encrypt(cfg.apiKey(), cfg.publicKey()))
                    .header("Origin", "*")
                    .retrieve()
                    // Vodacom answers a refused session with a non-2xx status
                    // and its reason in the body. Letting the status throw
                    // would discard the one sentence that says what is wrong.
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            // Says what actually went wrong. Blaming the credentials for what
            // may have been a network failure sends an operator to re-copy a key
            // that was right all along.
            log.warn("Vodacom M-Pesa getSession failed", e);
            throw new IllegalStateException("Could not reach Vodacom M-Pesa to open a session: "
                    + e.getMessage());
        }
        String id = response == null ? null : response.path("output_SessionID").asString(null);
        if (id == null || id.isBlank() || !"INS-0".equals(code(response))) {
            throw new IllegalStateException("Vodacom M-Pesa would not open a session: "
                    + describe(response, code(response)));
        }
        sessionBearer = encrypt(id, cfg.publicKey());
        // Vodacom says an hour. Five minutes short, so a session cannot expire
        // between being fetched and being used on a call that then blocks while
        // somebody looks for their phone.
        sessionExpiresAt = Instant.now().plus(Duration.ofMinutes(55));
        return sessionBearer;
    }

    /**
     * RSA under Vodacom's public key, base64.
     *
     * <p>{@code PKCS1Padding}, not OAEP. Both are RSA, both encrypt without
     * complaint, and only one of them can be decrypted at the other end — the
     * wrong choice fails at authentication with a message about credentials.
     */
    static String encrypt(String value, PublicKey key) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(
                    cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Vodacom M-Pesa credentials could not be encrypted", e);
        }
    }

    /**
     * The public key as the portal hands it over.
     *
     * <p>Copied out of a web page, so it arrives with line breaks in it and
     * sometimes with PEM headers the portal did not show. Both are stripped
     * rather than rejected: an operator who pasted their key correctly should
     * not be told it is wrong because the textarea wrapped it.
     */
    static PublicKey publicKey(String pasted) throws Exception {
        String base64 = pasted
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
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
