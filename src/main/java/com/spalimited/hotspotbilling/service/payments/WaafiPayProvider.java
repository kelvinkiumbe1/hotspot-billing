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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * WaafiPay — Somalia, via Hormuud's EVC Plus.
 *
 * <p>Worth far more than Somalia's size suggests. Mobile money there is close to
 * universal — higher penetration than Kenya — the country is full of small
 * independent ISPs, and no aggregator on the continent reaches any of them.
 * Before this there was no way to take a Somali payment at all.
 *
 * <h2>It is not shaped like anything else here</h2>
 *
 * <p><b>Every response is HTTP 200.</b> Missing parameters, bad credentials, a
 * declined payment — all of them arrive as 200 with the failure inside the body.
 * Verified against the live API. Any code that reads the status line would treat
 * every single failure as a success, which on this rail means giving internet
 * away.
 *
 * <p><b>There is no status endpoint.</b> Asked directly, the API recognises
 * {@code API_PURCHASE} and {@code API_PREAUTHORIZE} and answers every other
 * service name — including one invented for the test — with the same
 * {@code E10309 Bad Request}. So the purchase response is the outcome and there
 * is no second chance to ask. That is why {@link Charge#settledNow} exists: the
 * payment is settled from the charge itself or never.
 *
 * <p><b>There is no bearer token.</b> The merchant id, API user and API key ride
 * in the body of every request. Nothing is cached because there is no session to
 * cache.
 *
 * <p><b>It collects dollars.</b> Somalia is dollarised in practice and EVC Plus
 * prices in USD; the shilling barely circulates. So the currency check is against
 * USD, not SOS.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WaafiPayProvider implements PaymentProvider {

    private static final Set<Country> MARKETS = Set.of(Country.SO);

    /** What WaafiPay wants a timestamp to look like. Not ISO-8601. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Response codes that mean the money moved.
     *
     * <p>{@code 2001} with {@code errorCode} "0". Both are checked, because the
     * body carries a code for the request and a code for the error separately and
     * a success has to satisfy both.
     */
    private static final String APPROVED = "2001";

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PaymentEndpoints endpoints;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.WAAFIPAY;
    }

    @Override
    public boolean usable() {
        return availableHere() && currencyAgrees() && config() != null;
    }

    /**
     * Nothing to poll, and this is not an omission.
     *
     * <p>WaafiPay has no transaction-status service — its own API says so when
     * asked. The purchase answers synchronously and that answer is the only one
     * there will ever be.
     */
    @Override
    public boolean pollable() {
        return false;
    }

    /** True where WaafiPay is worth offering at all. */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("WaafiPay", MARKETS,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean currencyAgrees() {
        try {
            return MarketGuard.currencyAgrees("WaafiPay", Country.SO,
                    portalSettings.settings().getCurrencyCode());
        } catch (Exception e) {
            return false;
        }
    }

    private record Config(String merchantUid, String apiUserId, String apiKey) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.WAAFIPAY)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getShortCode()) || blank(g.getConsumerKey())
                || blank(g.getSecretKey())) {
            return null;
        }
        return new Config(g.getShortCode().trim(), g.getConsumerKey().trim(),
                g.getSecretKey().trim());
    }

    /**
     * Charges the customer's wallet and reports what happened.
     *
     * <p>Synchronous: the customer is prompted on their handset and this call
     * waits for them. The response is the outcome, so a success comes back as a
     * {@link Charge} already carrying its settlement and a refusal throws.
     *
     * <p>An ambiguous ending — a timeout, a dropped connection — throws too, and
     * that is a deliberate and uncomfortable choice. Every other rail here would
     * leave it for the sweep, but there is nothing to sweep with: no callback and
     * no status service. Telling the customer it failed at least sends them to
     * try again or to the operator; leaving the payment pending would have it
     * silently failed a quarter of an hour later with nobody any the wiser. The
     * log says plainly that the outcome is unknown, because that is the case an
     * operator has to reconcile by hand.
     */
    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = availableHere() && currencyAgrees() ? config() : null;
        if (cfg == null) {
            throw new IllegalStateException("WaafiPay is not set up for this country");
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("referenceId", request.reference());
        transaction.put("invoiceId", request.reference());
        // Dollars, in major units, to two places. EVC Plus prices in USD.
        transaction.put("amount", request.amount().setScale(2, RoundingMode.HALF_UP)
                .toPlainString());
        transaction.put("currency", "USD");
        transaction.put("description", trim(request.description(), 160));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("merchantUid", cfg.merchantUid());
        params.put("apiUserId", cfg.apiUserId());
        params.put("apiKey", cfg.apiKey());
        params.put("paymentMethod", "MWALLET_ACCOUNT");
        params.put("payerInfo", Map.of("accountNo", msisdn(request.phoneNumber())));
        params.put("transactionInfo", transaction);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1.0");
        body.put("requestId", requestId);
        body.put("timestamp", LocalDateTime.now().format(STAMP));
        body.put("channelName", "WEB");
        body.put("serviceName", "API_PURCHASE");
        body.put("serviceParams", params);

        JsonNode response;
        try {
            response = client().post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(JsonNode.class);
        } catch (Exception e) {
            // Nothing can be asked afterwards, so this has to be surfaced rather
            // than parked. Loud in the log because it is the one case that needs
            // a person to check Hormuud's dashboard.
            log.error("WaafiPay purchase {} gave no answer ({}). The outcome is UNKNOWN and there "
                    + "is no status service to ask — check Hormuud's dashboard for reference {}.",
                    request.reference(), e.getMessage(), request.reference());
            throw new IllegalStateException("The payment could not be confirmed. "
                    + "Please check with your provider before trying again.");
        }

        if (!approved(response)) {
            throw new IllegalStateException(refusal(response));
        }
        JsonNode result = response.path("params");
        String transactionId = result.path("transactionId").asString(requestId);
        // Settled here, because there is nowhere else it could be settled.
        Settlement settlement = new Settlement(transactionId, request.reference(), true,
                amountOf(result, request.amount()), "USD", transactionId, null);
        // No page: the customer has already approved it on their handset.
        return new Charge(transactionId, null, settlement);
    }

    /**
     * Whether the body says the money moved.
     *
     * <p>Both codes, and the state. Package-private because everything about this
     * method is a place to be too generous, and being too generous here gives
     * internet away: every failure arrives as HTTP 200, so this is the only thing
     * standing between a declined payment and a voucher.
     */
    static boolean approved(JsonNode response) {
        if (response == null) {
            return false;
        }
        String responseCode = response.path("responseCode").asString("");
        String errorCode = response.path("errorCode").asString("");
        if (!APPROVED.equals(responseCode) || !("0".equals(errorCode) || errorCode.isBlank())) {
            return false;
        }
        // And the transaction's own word for it, where there is one.
        String state = response.path("params").path("state").asString("");
        return state.isBlank() || "APPROVED".equalsIgnoreCase(state)
                || "SUCCESS".equalsIgnoreCase(state);
    }

    /**
     * Nothing may settle this rail from outside.
     *
     * <p>No webhook exists, so anything arriving would be somebody guessing at a
     * reference, and this endpoint mints vouchers.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        throw Signatures.reject("WaafiPay",
                "this rail has no webhook; a purchase is settled where it is made");
    }

    /**
     * The number as Hormuud identifies a subscriber.
     *
     * <p>International, digits only, no plus — {@code 252611234567}. Airtel next
     * door wants the opposite, and given the wrong form this finds nobody.
     */
    static String msisdn(String phoneNumber) {
        return phoneNumber == null ? null : phoneNumber.replaceAll("\\D", "");
    }

    // ---------------------------------------------------------------- plumbing

    private RestClient client() {
        // The modern client rather than HttpURLConnection, for the same reason
        // Vodacom needs it: nothing here should be silently reshaped on the way
        // out. A generous read timeout because the customer is being prompted on
        // their handset while this call is open.
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().baseUrl(endpoints.waafipay()).requestFactory(factory).build();
    }

    /** What WaafiPay said, in its own words. */
    private static String refusal(JsonNode response) {
        if (response != null) {
            String message = response.path("responseMsg").asString(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
            String error = response.path("errorCode").asString(null);
            if (error != null && !error.isBlank()) {
                return "WaafiPay refused the payment (" + error + ")";
            }
        }
        return "WaafiPay refused the payment";
    }

    private static BigDecimal amountOf(JsonNode result, BigDecimal asked) {
        String charged = result.path("txAmount").asString(null);
        if (charged == null || charged.isBlank()) {
            return asked;
        }
        try {
            return new BigDecimal(charged);
        } catch (NumberFormatException e) {
            return asked;
        }
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
