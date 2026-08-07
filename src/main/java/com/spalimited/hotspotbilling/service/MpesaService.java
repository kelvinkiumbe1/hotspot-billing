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
