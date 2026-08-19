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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Konnect — Tunisia.
 *
 * <p>Reaches what Tunisians actually have: the Konnect and Flouci wallets,
 * e-DINAR (the post office's card, which a great many people hold and no
 * international processor touches), and ordinary bank cards. Stripe does not
 * serve Tunisia and neither Paystack nor Flutterwave collects dinars, so before
 * this there was no way to take a Tunisian payment at all.
 *
 * <h2>The dinar has a thousand millimes</h2>
 *
 * <p>This is the thing to get right. Konnect takes the amount in millimes, and
 * TND is one of the handful of currencies with <em>three</em> decimal places
 * rather than two — so ten dinars is 10000, not 1000. Every other minor-unit
 * rail in this package multiplies by a hundred; doing that here undercharges by
 * a factor of ten on every sale, quietly, and the operator would find out from
 * their settlement report. See {@link #millimes}.
 *
 * <p>The callback is unsigned and arrives as a GET carrying a payment reference,
 * so it is treated the way MTN's is: a hint to go and ask. Believing it would let
 * anyone who learned a reference mark a payment successful.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KonnectProvider implements PaymentProvider {

    /** Tunisia. Konnect is domestic and licensed there. */
    private static final Set<Country> MARKETS = Set.of(Country.TN);

    /**
     * How many millimes in a dinar.
     *
     * <p>A thousand, and named rather than inlined because {@code 100} is what
     * every other rail in this package uses and this is the one place that is
     * wrong. ISO 4217 gives TND an exponent of 3, along with the Bahraini,
     * Iraqi, Jordanian, Kuwaiti, Libyan and Omani currencies.
     */
    private static final int MILLIMES_PER_DINAR = 1000;

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PaymentEndpoints endpoints;
    private final PublicUrls urls;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.KONNECT;
    }

    @Override
    public boolean usable() {
        return availableHere() && currencyAgrees() && config() != null;
    }

    /**
     * Asking Konnect is the trustworthy answer.
     *
     * <p>Its callback carries no signature, so the status endpoint is what
     * actually settles a payment — and the same call is what rescues one whose
     * callback never arrived.
     */
    @Override
    public boolean pollable() {
        return true;
    }

    /** True where Konnect is worth offering at all. */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("Konnect", MARKETS,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean currencyAgrees() {
        try {
            return MarketGuard.currencyAgrees("Konnect", Country.TN,
                    portalSettings.settings().getCurrencyCode());
        } catch (Exception e) {
            return false;
        }
    }

    private record Config(String baseUrl, String apiKey, String walletId) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.KONNECT)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getSecretKey()) || blank(g.getShortCode())) {
            return null;
        }
        boolean live = g.getEnvironment() == PaymentGateway.Environment.PRODUCTION;
        return new Config(endpoints.konnect(live), g.getSecretKey().trim(),
                g.getShortCode().trim());
    }

    // ------------------------------------------------------------------ charge

    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = availableHere() && currencyAgrees() ? config() : null;
        if (cfg == null) {
            throw new IllegalStateException("Konnect is not set up for this country");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiverWalletId", cfg.walletId());
        body.put("token", "TND");
        // Millimes. A thousand to the dinar, not a hundred.
        body.put("amount", millimes(request.amount()));
        body.put("type", "immediate");
        body.put("description", trim(request.description(), 240));
        // Everything the merchant has enabled. Listing them explicitly rather
        // than leaving it to Konnect's default, because e-DINAR is the one a
        // Tunisian customer is most likely to hold and least likely to be
        // offered by anybody else.
        body.put("acceptedPaymentMethods", List.of("wallet", "bank_card", "e-DINAR", "flouci"));
        // Minutes. Long enough to find a card, short enough that an abandoned
        // payment does not sit open against the reference.
        body.put("lifespan", 15);
        body.put("checkoutForm", false);
        body.put("addPaymentFeesToAmount", false);
        body.put("firstName", "WiFi");
        body.put("lastName", "Customer");
        body.put("phoneNumber", request.phoneNumber() == null ? "" : request.phoneNumber());
        body.put("email", PaymentEmails.forCustomer(request.email(), request.phoneNumber()));
        // Ours. What the status document quotes back, and how a settlement finds
        // its way to a payment.
        body.put("orderId", request.reference());
        body.put("theme", "light");

        String origin = urls.origin();
        if (origin != null && !origin.isBlank()) {
            body.put("webhook", origin + "/api/payments/konnect/webhook");
            // Silent, because our webhook answers with a word rather than a page
            // and Konnect would otherwise show it to the customer.
            body.put("silentWebhook", true);
            body.put("successUrl", origin + "/?paid=" + enc(request.reference()));
            body.put("failUrl", origin + "/?failed=" + enc(request.reference()));
        } else {
            // Not fatal, unlike Paynow. Konnect can be asked how a payment ended,
            // so the sweep settles it a minute later instead of never.
            log.info("No public address configured, so Konnect gets no webhook URL — "
                    + "payments will settle on the reconcile sweep instead.");
        }

        JsonNode response;
        try {
            response = client(cfg).post()
                    .uri("/payments/init-payment")
                    .header("x-api-key", cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Konnect init-payment failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }

        String reference = response == null ? null : response.path("paymentRef").asString(null);
        String payUrl = response == null ? null : response.path("payUrl").asString(null);
        if (reference == null || reference.isBlank() || payUrl == null || payUrl.isBlank()) {
            throw new IllegalStateException(refusal(response));
        }
        return new Charge(reference, payUrl);
    }

    /**
     * Dinars to millimes.
     *
     * <p>Times a thousand. The temptation is to reach for the hundred every other
     * minor-unit rail here uses, and that undercharges by a factor of ten on
     * every single sale without erroring once.
     */
    static long millimes(BigDecimal dinars) {
        return dinars.multiply(BigDecimal.valueOf(MILLIMES_PER_DINAR))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    // ----------------------------------------------------------------- outcome

    /**
     * What Konnect says about a payment.
     *
     * <p>The only trustworthy answer, since the callback is unsigned — and the
     * same call the sweep uses for one whose callback never came.
     */
    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode response;
        try {
            response = client(cfg).get()
                    .uri("/payments/{ref}", providerRef)
                    .header("x-api-key", cfg.apiKey())
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Konnect status for {} unavailable: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(response, providerRef);
    }

    /**
     * A payment document, turned into a verdict.
     *
     * <p>Package-private so the states can be pinned without a socket. The
     * important one is the one that returns empty: {@code pending} is a customer
     * still on Konnect's page, and calling that a failure cancels a live sale.
     */
    static Optional<Settlement> read(JsonNode response, String providerRef) {
        if (response == null) {
            return Optional.empty();
        }
        JsonNode payment = response.path("payment").isMissingNode()
                ? response : response.path("payment");
        String status = payment.path("status").asString("");
        String orderId = payment.path("orderId").asString(null);
        BigDecimal amount = dinars(payment);
        String receipt = payment.path("id").asString(providerRef);

        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "completed" -> Optional.of(new Settlement(
                    providerRef, orderId, true, amount, "TND", receipt, null));
            // Konnect distinguishes an expired payment from a refused one. Both
            // are over, and neither took any money.
            case "failed", "expired", "cancelled", "canceled" -> Optional.of(new Settlement(
                    providerRef, orderId, false, amount, "TND", null, status));
            default -> Optional.empty();
        };
    }

    /**
     * A callback from Konnect, which proves nothing on its own.
     *
     * <p>Unsigned, so the reference is read out only far enough to know which
     * payment to ask about, and the verdict comes from asking. Konnect normally
     * sends this as a GET with the reference in the query string — see the
     * controller — and this handles the POST shape for the same reason MTN's does:
     * the delivery method is configurable and the body is trusted either way,
     * which is to say not at all.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String reference = referenceIn(rawBody);
        if (reference == null || reference.isBlank()) {
            throw Signatures.reject("Konnect", "no payment reference to check");
        }
        return poll(reference);
    }

    /** The reference a callback body names, if it names one. */
    static String referenceIn(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        for (String key : new String[]{"\"payment_ref\"", "\"paymentRef\""}) {
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

    private RestClient client(Config cfg) {
        return RestClient.create(cfg.baseUrl());
    }

    private static BigDecimal dinars(JsonNode payment) {
        String raw = payment.path("amount").asString(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            // Back out of millimes, or the receipt says ten thousand dinars.
            return new BigDecimal(raw).divide(BigDecimal.valueOf(MILLIMES_PER_DINAR));
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** Konnect's own words where it gave any. */
    private static String refusal(JsonNode response) {
        if (response != null) {
            for (String field : new String[]{"message", "error", "errors"}) {
                JsonNode node = response.path(field);
                String value = node.isArray() && !node.isEmpty()
                        ? node.get(0).path("message").asString(null)
                        : node.asString(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return "Konnect would not start the payment";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
