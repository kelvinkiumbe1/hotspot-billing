package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Flutterwave — cards and mobile money across most of Africa.
 *
 * <p>Unlike Paystack, amounts go in the major unit, so a naira price is sent as
 * written. Multiplying by a hundred here would overcharge by a hundred times,
 * which is why the two providers are deliberately not sharing that code.
 *
 * <p>Its webhooks are not signed. They carry a {@code verif-hash} header
 * holding a secret hash the operator sets in the Flutterwave dashboard, and
 * verification is a comparison against it — weaker than an HMAC over the body,
 * because the same header value arrives on every request and proves only that
 * the sender knows the shared secret, not that the body is untampered. That is
 * all Flutterwave offers, so the amount on every settlement is checked against
 * what we asked for downstream, which catches a tampered body anyway.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlutterwaveProvider implements PaymentProvider {

    private static final String BASE = "https://api.flutterwave.com/v3";

    private final PaymentGatewayService gateways;
    private final ObjectMapper mapper;
    private final RestClient client = RestClient.create(BASE);

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.FLUTTERWAVE;
    }

    @Override
    public boolean usable() {
        return config() != null;
    }

    private PaymentGateway config() {
        return gateways.find(PaymentGateway.Kind.FLUTTERWAVE)
                .filter(PaymentGateway::isActive)
                .filter(g -> g.getSecretKey() != null && !g.getSecretKey().isBlank())
                .orElse(null);
    }

    @Override
    public Charge charge(ChargeRequest request) {
        PaymentGateway cfg = config();
        if (cfg == null) {
            throw new IllegalStateException("Flutterwave is not configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tx_ref", request.reference());
        // Major unit, unlike Paystack. Sending minor units here would charge a
        // hundred times the price.
        body.put("amount", request.amount().toPlainString());
        body.put("currency", request.currency());
        body.put("payment_options", "card,mobilemoney,ussd");
        body.put("customer", Map.of(
                "email", request.email() != null && request.email().contains("@")
                        ? request.email() : request.phoneNumber() + "@no-email.invalid",
                "phonenumber", request.phoneNumber() == null ? "" : request.phoneNumber()));
        body.put("customizations", Map.of("title",
                request.description() == null ? "Internet access" : request.description()));

        JsonNode response;
        try {
            response = client.post()
                    .uri("/payments")
                    .header("Authorization", "Bearer " + cfg.getSecretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Flutterwave payment start failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the card payment. Please try again.");
        }
        if (response == null || !"success".equalsIgnoreCase(response.path("status").asString(""))) {
            throw new IllegalStateException("Flutterwave refused the payment: "
                    + (response == null ? "no response" : response.path("message").asString("")));
        }
        return new Charge(request.reference(), response.path("data").path("link").asString(null));
    }

    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        PaymentGateway cfg = config();
        if (cfg == null) {
            throw Signatures.reject("Flutterwave", "not configured");
        }
        String expected = cfg.getWebhookSecret();
        if (expected == null || expected.isBlank()) {
            // Refusing outright rather than accepting unverified: an endpoint
            // that mints vouchers must not be open because a field was skipped.
            throw Signatures.reject("Flutterwave", "no verification hash is configured");
        }
        if (!Signatures.matches(expected, Signatures.header(headers, "verif-hash"))) {
            throw Signatures.reject("Flutterwave", "verif-hash did not match");
        }

        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw Signatures.reject("Flutterwave", "body was not readable JSON");
        }
        JsonNode data = event.path("data");
        String reference = data.path("tx_ref").asString(null);
        String providerRef = data.path("id").asString(reference);
        String status = data.path("status").asString("");

        if (!"charge.completed".equalsIgnoreCase(event.path("event").asString(""))) {
            log.debug("Flutterwave event {} needs no action", event.path("event").asString(""));
            return Optional.empty();
        }
        boolean paid = "successful".equalsIgnoreCase(status);
        BigDecimal amount;
        try {
            amount = new BigDecimal(data.path("amount").asString("0"));
        } catch (RuntimeException e) {
            // Rejected outright rather than passed on as null. This used to
            // rely on a null being read as a mismatch by PaymentService, which
            // made "no amount" and "the wrong amount" the same thing — and that
            // conflation is what marked every webhook-settled Airtel payment
            // failed, since Airtel's enquiry reports no amount at all.
            throw Signatures.reject("Flutterwave", "amount was not a number");
        }
        return Optional.of(new Settlement(providerRef, reference, paid, amount,
                data.path("currency").asString(null),
                data.path("flw_ref").asString(null),
                paid ? null : "Flutterwave reported: " + status));
    }
}
