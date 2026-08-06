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

/**
 * Safaricom Daraja integration: OAuth token + Lipa na M-Pesa STK push.
 */
@Service
@Slf4j
public class MpesaService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MpesaProperties props;
    private final RestClient restClient;

    public MpesaService(MpesaProperties props) {
        this.props = props;
        this.restClient = RestClient.create(props.baseUrl());
    }

    /**
     * Sends an STK push to the customer's phone and returns the Daraja
     * CheckoutRequestID used to correlate the async callback.
     */
    public String stkPush(String phoneNumber, BigDecimal amount, String accountReference) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        String password = Base64.getEncoder().encodeToString(
                (props.shortCode() + props.passkey() + timestamp).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = Map.ofEntries(
                Map.entry("BusinessShortCode", props.shortCode()),
                Map.entry("Password", password),
                Map.entry("Timestamp", timestamp),
                Map.entry("TransactionType", "CustomerPayBillOnline"),
                Map.entry("Amount", amount.toBigInteger()),
                Map.entry("PartyA", phoneNumber),
                Map.entry("PartyB", props.shortCode()),
                Map.entry("PhoneNumber", phoneNumber),
                Map.entry("CallBackURL", props.callbackUrl()),
                Map.entry("AccountReference", accountReference),
                Map.entry("TransactionDesc", "Hotspot internet access"));

        JsonNode response = restClient.post()
                .uri("/mpesa/stkpush/v1/processrequest")
                .header("Authorization", "Bearer " + fetchAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || response.path("ResponseCode").asInt(-1) != 0) {
            throw new IllegalStateException("STK push rejected by Daraja: " + response);
        }
        String checkoutRequestId = response.path("CheckoutRequestID").asText();
        log.info("STK push sent to {} (CheckoutRequestID={})", phoneNumber, checkoutRequestId);
        return checkoutRequestId;
    }

    private String fetchAccessToken() {
        String credentials = Base64.getEncoder().encodeToString(
                (props.consumerKey() + ":" + props.consumerSecret()).getBytes(StandardCharsets.UTF_8));
        JsonNode response = restClient.get()
                .uri("/oauth/v1/generate?grant_type=client_credentials")
                .header("Authorization", "Basic " + credentials)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("access_token").isMissingNode()) {
            throw new IllegalStateException("Failed to obtain Daraja access token");
        }
        return response.get("access_token").asText();
    }
}
