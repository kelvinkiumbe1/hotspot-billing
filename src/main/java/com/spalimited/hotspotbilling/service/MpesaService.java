package com.spalimited.hotspotbilling.service;

import tools.jackson.databind.JsonNode;
import com.spalimited.hotspotbilling.config.MpesaProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safaricom Daraja integration: OAuth token + Lipa na M-Pesa STK push.
 *
 * <p>Credentials are read per call rather than at construction, because an
 * operator can change them from the admin and must not have to be told to
 * restart before the change takes effect. Only the callback URL still comes
 * from configuration — it depends on the deployment's public address, not
 * on which gateway is selected.
 */
@Service
@Slf4j
public class MpesaService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MpesaProperties props;
    private final PaymentGatewayService gatewayService;

    /**
     * One client per base URL. Building a RestClient on every call would
     * throw away connection pooling, and there are only ever two hosts
     * (sandbox and production).
     */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public MpesaService(MpesaProperties props, PaymentGatewayService gatewayService) {
        this.props = props;
        this.gatewayService = gatewayService;
    }

    private RestClient clientFor(String baseUrl) {
        return clients.computeIfAbsent(baseUrl, RestClient::create);
    }

    /**
     * Sends an STK push to the customer's phone and returns the Daraja
     * CheckoutRequestID used to correlate the async callback.
     */
    public String stkPush(String phoneNumber, BigDecimal amount, String accountReference) {
        PaymentGatewayService.DarajaConfig cfg = gatewayService.daraja();
        if (!cfg.usable()) {
            throw new IllegalStateException(
                    "M-Pesa is not set up yet — add your Daraja details under Settings → Payments");
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        String password = Base64.getEncoder().encodeToString(
                (cfg.shortCode() + cfg.passkey() + timestamp).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = Map.ofEntries(
                Map.entry("BusinessShortCode", cfg.shortCode()),
                Map.entry("Password", password),
                Map.entry("Timestamp", timestamp),
                Map.entry("TransactionType", "CustomerPayBillOnline"),
                Map.entry("Amount", amount.toBigInteger()),
                Map.entry("PartyA", phoneNumber),
                Map.entry("PartyB", cfg.shortCode()),
                Map.entry("PhoneNumber", phoneNumber),
                Map.entry("CallBackURL", props.callbackUrl()),
                Map.entry("AccountReference", accountReference),
                Map.entry("TransactionDesc", "Hotspot internet access"));

        JsonNode response = clientFor(cfg.baseUrl()).post()
                .uri("/mpesa/stkpush/v1/processrequest")
                .header("Authorization", "Bearer " + fetchAccessToken(cfg))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || response.path("ResponseCode").asInt(-1) != 0) {
            throw new IllegalStateException("STK push rejected by Daraja: " + response);
        }
        String checkoutRequestId = response.path("CheckoutRequestID").asText();
        log.info("STK push sent to {} ({}, CheckoutRequestID={})",
                phoneNumber, cfg.live() ? "production" : "sandbox", checkoutRequestId);
        return checkoutRequestId;
    }

    /**
     * Asks Daraja for the final outcome of an STK push, to reconcile a payment
     * whose callback never arrived. Returns the transaction ResultCode: 0 =
     * paid, any other value = failed/cancelled. Returns null when Daraja won't
     * say yet — the customer still has the prompt open, or a transient error —
     * so the caller leaves the payment PENDING and tries again later.
     */
    public Integer queryStkStatus(String checkoutRequestId) {
        PaymentGatewayService.DarajaConfig cfg = gatewayService.daraja();
        if (!cfg.usable() || checkoutRequestId == null || checkoutRequestId.isBlank()) {
            return null;
        }
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        String password = Base64.getEncoder().encodeToString(
                (cfg.shortCode() + cfg.passkey() + timestamp).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> body = Map.of(
                "BusinessShortCode", cfg.shortCode(),
                "Password", password,
                "Timestamp", timestamp,
                "CheckoutRequestID", checkoutRequestId);
        JsonNode response;
        try {
            response = clientFor(cfg.baseUrl()).post()
                    .uri("/mpesa/stkpushquery/v1/query")
                    .header("Authorization", "Bearer " + fetchAccessToken(cfg))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            // While the customer still has the STK prompt open, Daraja answers
            // this query with an error ("The transaction is being processed"),
            // not a result. Treat any error as "not decided yet".
            log.debug("STK query for {} not decided yet: {}", checkoutRequestId, e.getMessage());
            return null;
        }
        if (response == null || response.path("ResultCode").isMissingNode()) {
            return null;
        }
        try {
            return Integer.parseInt(response.path("ResultCode").asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Asks Daraja to confirm a real M-Pesa transaction by its confirmation
     * code, so a customer who paid by hand (Paybill/Till) can claim access
     * without us trusting a pasted, forgeable SMS. Like B2C this is async: the
     * call is only accepted here, and the actual details arrive later on the
     * ResultURL. Returns the ConversationID to correlate that result, or null
     * when verification isn't available or Daraja rejected the request.
     */
    public String queryTransactionStatus(String receipt) {
        PaymentGatewayService.DarajaConfig cfg = gatewayService.daraja();
        String resultUrl = deriveCallbackUrl("transaction-result");
        if (!cfg.canVerifyTransactions() || receipt == null || receipt.isBlank() || resultUrl == null) {
            return null;
        }
        Map<String, Object> body = Map.ofEntries(
                Map.entry("Initiator", cfg.initiatorName()),
                Map.entry("SecurityCredential", cfg.securityCredential()),
                Map.entry("CommandID", "TransactionStatusQuery"),
                Map.entry("TransactionID", receipt),
                Map.entry("PartyA", cfg.shortCode()),
                Map.entry("IdentifierType", "4"), // 4 = organisation shortcode
                Map.entry("ResultURL", resultUrl),
                Map.entry("QueueTimeOutURL", deriveCallbackUrl("transaction-timeout")),
                Map.entry("Remarks", "Customer voucher claim"),
                Map.entry("Occasion", "voucher-claim"));
        JsonNode response;
        try {
            response = clientFor(cfg.baseUrl()).post()
                    .uri("/mpesa/transactionstatus/v1/query")
                    .header("Authorization", "Bearer " + fetchAccessToken(cfg))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Transaction status query for {} rejected: {}", receipt, e.getMessage());
            return null;
        }
        if (response == null || response.path("ResponseCode").asInt(-1) != 0) {
            log.warn("Transaction status query for {} not accepted: {}", receipt, response);
            return null;
        }
        return response.path("ConversationID").asText();
    }

    /**
     * Builds a Daraja result/timeout URL from the configured callback URL by
     * swapping its final segment, so all the async endpoints share one public
     * base without a second setting to keep in sync.
     */
    private String deriveCallbackUrl(String lastSegment) {
        String base = props.callbackUrl();
        if (base == null || base.isBlank() || !base.contains("/")) {
            return null;
        }
        return base.substring(0, base.lastIndexOf('/') + 1) + lastSegment;
    }

    /**
     * Checks credentials against Daraja without moving any money, so an
     * operator finds out they are wrong while setting up rather than when a
     * customer tries to pay.
     */
    public void verifyCredentials(PaymentGatewayService.DarajaConfig cfg) {
        if (!cfg.usable()) {
            throw new IllegalStateException("Fill in all four Daraja fields first");
        }
        fetchAccessToken(cfg);
    }

    private String fetchAccessToken(PaymentGatewayService.DarajaConfig cfg) {
        String credentials = Base64.getEncoder().encodeToString(
                (cfg.consumerKey() + ":" + cfg.consumerSecret()).getBytes(StandardCharsets.UTF_8));
        JsonNode response;
        try {
            response = clientFor(cfg.baseUrl()).get()
                    .uri("/oauth/v1/generate?grant_type=client_credentials")
                    .header("Authorization", "Basic " + credentials)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            // Daraja answers a bad key with a 400, which would otherwise
            // surface as an unreadable stack trace during setup.
            throw new IllegalStateException(
                    "Daraja rejected those credentials — check the consumer key and secret, "
                            + "and that they belong to the "
                            + (cfg.live() ? "production" : "sandbox") + " app", e);
        }
        if (response == null || response.path("access_token").isMissingNode()) {
            throw new IllegalStateException("Daraja did not return an access token");
        }
        return response.get("access_token").asText();
    }
}
