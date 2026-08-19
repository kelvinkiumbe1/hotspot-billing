package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.i18n.Country;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * CMI — Morocco.
 *
 * <p>Centre Monétique Interbancaire clears very nearly every Moroccan card, and
 * nothing else here reaches the country: Stripe does not serve Morocco, and
 * neither Paystack nor Flutterwave collects dirhams. So this is the rail or there
 * is no rail.
 *
 * <h2>It is not an API</h2>
 *
 * <p>Every other rail in this package is a server-to-server call. CMI is a
 * browser form post: the customer's own browser submits a set of signed fields to
 * CMI's 3-D Secure gateway, pays there, and is posted back to us with a signed
 * result. Nothing here ever opens a socket to CMI.
 *
 * <p>Which is why {@link #charge} returns a URL on <em>our</em> host rather than
 * on CMI's — {@code checkoutUrl} can only be somewhere to send a browser with a
 * GET, and this flow needs a POST. That URL renders a form that submits itself.
 * See {@code CmiRedirectController}.
 *
 * <p>The signature is the whole of the security. Every field except the hash
 * itself, sorted by name, escaped, joined with pipes, salted with the store key
 * and hashed — in both directions. Get the escaping or the ordering wrong and CMI
 * refuses the payment, or worse, a forged return is believed. See
 * {@link #hash}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CmiProvider implements PaymentProvider {

    private static final Set<Country> MARKETS = Set.of(Country.MA);

    /** ISO 4217 numeric for the dirham. CMI takes the number, not "MAD". */
    private static final String MAD_NUMERIC = "504";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PaymentEndpoints endpoints;
    private final PublicUrls urls;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.CMI;
    }

    @Override
    public boolean usable() {
        return availableHere() && currencyAgrees() && config() != null
                && urls.origin() != null && !urls.origin().isBlank();
    }

    /**
     * Nothing to poll.
     *
     * <p>CMI has no status API to ask — the result arrives as a signed post back
     * to us and that is the only channel there is. A payment whose customer
     * closed the browser is timed out by the sweep, which is the honest answer.
     */
    @Override
    public boolean pollable() {
        return false;
    }

    /** True where CMI is worth offering at all. */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("CMI", MARKETS,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean currencyAgrees() {
        try {
            return MarketGuard.currencyAgrees("CMI", Country.MA,
                    portalSettings.settings().getCurrencyCode());
        } catch (Exception e) {
            return false;
        }
    }

    private record Config(String clientId, String storeKey) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.CMI)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getShortCode()) || blank(g.getSecretKey())) {
            return null;
        }
        return new Config(g.getShortCode().trim(), g.getSecretKey().trim());
    }

    /**
     * Starts a payment by handing back somewhere to send the browser.
     *
     * <p>No call is made. Everything CMI needs is assembled when the customer
     * arrives at the redirect page, because that is the moment the form has to
     * exist — and a hash built minutes earlier would be no fresher.
     */
    @Override
    public Charge charge(ChargeRequest request) {
        if (!availableHere() || !currencyAgrees() || config() == null) {
            throw new IllegalStateException("CMI is not set up for this country");
        }
        String origin = urls.origin();
        if (origin == null || origin.isBlank()) {
            // Unlike Konnect there is no way to ask CMI anything, so with nowhere
            // to be posted back to the payment could never be settled at all.
            throw new IllegalStateException("CMI needs a public address to be paid back to — "
                    + "set one before switching it on");
        }
        // Ours is the handle: CMI quotes oid back on the result, and there is no
        // id of its own until the customer has actually paid.
        return new Charge(request.reference(),
                origin + "/api/payments/cmi/redirect?ref="
                        + java.net.URLEncoder.encode(request.reference(), StandardCharsets.UTF_8));
    }

    /**
     * The signed fields the browser posts to CMI.
     *
     * <p>Built here rather than in the controller so the signature and the fields
     * it covers cannot drift apart.
     */
    public Optional<Form> form(String reference, BigDecimal amount, String phoneNumber,
                               String email) {
        Config cfg = availableHere() && currencyAgrees() ? config() : null;
        String origin = urls.origin();
        if (cfg == null || origin == null || origin.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("clientid", cfg.clientId());
        // 3D_PAY_HOSTING: CMI hosts the card form and runs 3-D Secure. The
        // alternative puts the card number through this server, which is not
        // somewhere a card number should ever be.
        fields.put("storetype", "3D_PAY_HOSTING");
        fields.put("trantype", "Auth");
        fields.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        fields.put("currency", MAD_NUMERIC);
        fields.put("oid", reference);
        fields.put("okUrl", origin + "/api/payments/cmi/return");
        fields.put("failUrl", origin + "/api/payments/cmi/return");
        fields.put("callbackUrl", origin + "/api/payments/cmi/return");
        fields.put("lang", "fr");
        fields.put("refreshtime", "3");
        fields.put("hashAlgorithm", "ver3");
        fields.put("encoding", "UTF-8");
        // Fresh per attempt. CMI folds it into the hash so two identical
        // payments do not produce an identical signature.
        fields.put("rnd", Long.toHexString(RANDOM.nextLong()));
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            fields.put("tel", phoneNumber);
        }
        fields.put("email", PaymentEmails.forCustomer(email, phoneNumber));
        fields.put("BillToName", "WiFi Customer");

        fields.put("hash", hash(fields, cfg.storeKey()));
        return Optional.of(new Form(endpoints.cmi(), fields));
    }

    /** Where to post, and what to post there. */
    public record Form(String action, Map<String, String> fields) {
    }

    /**
     * CMI's {@code ver3} hash, in both directions.
     *
     * <p>Every field except the hash itself and {@code encoding}, sorted by name
     * without regard to case, each value escaped, joined with {@code |}, then the
     * store key appended the same way — SHA-512, base64.
     *
     * <p>The escaping is the part that looks like a detail and is not. A value
     * containing a pipe would otherwise shift every field after it by one
     * position, producing a hash that is wrong in a way nobody could read from
     * the error. A backslash has to be escaped first or escaping the pipe would
     * be undone by it.
     *
     * <p>Sorted case-insensitively because CMI's own fields are a mixture --
     * {@code okUrl} beside {@code oid} beside {@code BillToName} -- and sorting
     * by byte value puts every capital before every lowercase, which is a
     * different order and therefore a different hash.
     */
    static String hash(Map<String, String> fields, String storeKey) {
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        fields.forEach((key, value) -> {
            if (!key.equalsIgnoreCase("hash") && !key.equalsIgnoreCase("encoding")) {
                sorted.put(key, value);
            }
        });
        List<String> parts = new ArrayList<>();
        for (String value : sorted.values()) {
            parts.add(escape(value));
        }
        parts.add(escape(storeKey));
        String joined = String.join("|", parts);
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            return Base64.getEncoder().encodeToString(
                    sha512.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("CMI hash could not be computed", e);
        }
    }

    /** Backslash first, then pipe. The other order undoes itself. */
    private static String escape(String value) {
        String v = value == null ? "" : value;
        return v.replace("\\", "\\\\").replace("|", "\\|");
    }

    /**
     * The result CMI posts back, verified.
     *
     * <p>Takes the fields rather than raw bytes because this arrives as a form
     * post, not JSON, and the signature is over the fields rather than the body.
     * {@link #settle} exists for the interface and delegates here.
     */
    public Optional<Settlement> settleForm(Map<String, String> posted) {
        Config cfg = config();
        if (cfg == null) {
            throw Signatures.reject("CMI", "not configured");
        }
        String given = firstOf(posted.get("HASH"), posted.get("hash"));
        if (given == null || given.isBlank()) {
            throw Signatures.reject("CMI", "no signature on the result");
        }
        String expected = hash(posted, cfg.storeKey());
        if (!Signatures.matches(expected, given)) {
            throw Signatures.reject("CMI", "signature did not match");
        }

        String reference = firstOf(posted.get("oid"), posted.get("ReturnOid"));
        String procReturnCode = firstOf(posted.get("ProcReturnCode"), posted.get("procreturncode"));
        String response = firstOf(posted.get("Response"), posted.get("response"));
        // "00" and "Approved", both. CMI sends a Response for the transaction and
        // a ProcReturnCode for the processing, and a declined card can carry a
        // cheerful-looking one of them.
        boolean paid = "00".equals(procReturnCode) && "Approved".equalsIgnoreCase(response);
        String receipt = firstOf(posted.get("TransId"), posted.get("AuthCode"));

        BigDecimal amount = null;
        String raw = posted.get("amount");
        if (raw != null && !raw.isBlank()) {
            try {
                amount = new BigDecimal(raw.trim());
            } catch (NumberFormatException ignored) {
                // Left null, which reads as "not reported" rather than as zero.
            }
        }
        return Optional.of(new Settlement(reference, reference, paid, amount, "MAD",
                paid ? receipt : null,
                paid ? null : firstOf(posted.get("ErrMsg"), response, procReturnCode)));
    }

    /**
     * Not the way CMI settles anything.
     *
     * <p>The result is a form post and goes through {@link #settleForm}. This
     * refuses rather than trying to parse a body, because a JSON payload arriving
     * here would be somebody guessing.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        throw Signatures.reject("CMI",
                "CMI posts a signed form back; there is no JSON webhook");
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

    /** For the redirect page, so it can say who it is sending the customer to. */
    public String bankName() {
        return "CMI";
    }

    /** Lowercased once, so the controller does not have to know the shape. */
    static Map<String, String> lower(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k == null ? "" : k.toLowerCase(Locale.ROOT), v));
        return out;
    }
}
