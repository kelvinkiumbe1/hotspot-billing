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
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Chapa — Ethiopia, reaching telebirr, CBE Birr, Amole and M-Pesa Ethiopia.
 *
 * <p>Ethiopia was listed as unreachable, which was wrong. Telebirr is domestic
 * and none of the pan-African aggregators touch it, but Chapa is Ethiopian and
 * does, so an Ethiopian ISP is not stuck reconciling by hand after all.
 *
 * <p>Shaped like Paystack rather than like M-Pesa: the customer opens a hosted
 * page and chooses their wallet there. Chapa does publish a direct-charge call
 * that prompts a telebirr handset without the page, which would make this feel
 * like STK — it is deliberately not used here, because its exact shape is not
 * something worth guessing at with somebody else's money. The page flow is the
 * documented one and it works.
 *
 * <p>Amounts are major units. Birr are quoted whole, and multiplying by a
 * hundred the way Paystack wants would charge a hundred times too much.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChapaProvider implements PaymentProvider {

    // Address moved to PaymentEndpoints; the default there is this URL.

    private final PaymentGatewayService gateways;
    private final ObjectMapper mapper;
    private final PaymentEndpoints endpoints;
    /**
     * Built per call rather than frozen at construction, so the address can be
     * stood in front of by a test. Every other rail here already does this.
     */
    private RestClient client() {
        return RestClient.create(endpoints.chapa());
    }

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.CHAPA;
    }

    @Override
    public boolean usable() {
        return secret() != null;
    }

    @Override
    public boolean pollable() {
        return true;
    }

    private String secret() {
        return gateways.find(PaymentGateway.Kind.CHAPA)
                .filter(PaymentGateway::isActive)
                .map(PaymentGateway::getSecretKey)
                .filter(k -> k != null && !k.isBlank())
                .orElse(null);
    }

    private String webhookSecret() {
        return gateways.find(PaymentGateway.Kind.CHAPA)
                .map(PaymentGateway::getWebhookSecret)
                .filter(k -> k != null && !k.isBlank())
                .orElse(null);
    }

    @Override
    public Charge charge(ChargeRequest request) {
        String secret = secret();
        if (secret == null) {
            throw new IllegalStateException("Chapa is not configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // Major units. Birr are quoted whole; sending minor units the way card
        // processors want overcharges a hundredfold.
        body.put("amount", request.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        body.put("currency", request.currency());
        body.put("tx_ref", request.reference());
        // Chapa wants an email. A hotspot customer has a phone number and
        // nothing else, so one is derived rather than blocking the sale.
        body.put("email", request.email() != null && request.email().contains("@")
                ? request.email() : request.phoneNumber() + "@no-email.invalid");
        if (request.phoneNumber() != null) {
            body.put("phone_number", request.phoneNumber());
        }
        body.put("customization", Map.of(
                "title", "WiFi",
                "description", request.description() == null ? "" : request.description()));

        JsonNode response;
        try {
            response = client().post()
                    .uri("/transaction/initialize")
                    .header("Authorization", "Bearer " + secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Chapa initialise failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
        if (response == null || !"success".equalsIgnoreCase(response.path("status").asString(""))) {
            throw new IllegalStateException("Chapa refused the payment: "
                    + (response == null ? "no response" : response.path("message").asString("")));
        }
        // Chapa keys everything on the tx_ref we chose, not on an id of its own.
        return new Charge(request.reference(),
                response.path("data").path("checkout_url").asString(null));
    }

    /**
     * Asks Chapa how a charge ended.
     *
     * <p>Also what rescues a payment whose webhook never arrived, which is the
     * ordinary failure for a customer who closed the page after paying.
     */
    @Override
    public Optional<Settlement> poll(String providerRef) {
        String secret = secret();
        if (secret == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        JsonNode response;
        try {
            response = client().get()
                    .uri("/transaction/verify/{ref}", providerRef)
                    .header("Authorization", "Bearer " + secret)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.debug("Chapa verify failed for {}: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(response, providerRef);
    }

    /** Chapa's verify document, turned into a verdict. */
    static Optional<Settlement> read(JsonNode response, String providerRef) {
        if (response == null) {
            return Optional.empty();
        }
        JsonNode data = response.path("data");
        String state = data.path("status").asString("");
        BigDecimal amount = amountOf(data);

        return switch (state.toLowerCase()) {
            case "success" -> Optional.of(new Settlement(
                    providerRef, data.path("tx_ref").asString(providerRef), true, amount,
                    data.path("currency").asString(null),
                    data.path("reference").asString(providerRef), null));
            case "failed", "cancelled" -> Optional.of(new Settlement(
                    providerRef, data.path("tx_ref").asString(providerRef), false, amount,
                    data.path("currency").asString(null), null, state));
            // "pending" means the customer is still on the page. Reporting that
            // as unpaid cancels a sale that has not finished happening.
            default -> Optional.empty();
        };
    }

    /**
     * A Chapa webhook.
     *
     * <p>Signed with HMAC-SHA256 of the raw body using the webhook secret, and
     * checked before anything is read from it. Even then the verdict is taken
     * from a verify call rather than the body: a signature proves who sent the
     * message, not that the message still describes the truth.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String secret = webhookSecret();
        if (secret == null) {
            throw Signatures.reject("Chapa", "no webhook secret configured");
        }
        String given = Signatures.header(headers, "chapa-signature");
        if (given == null) {
            given = Signatures.header(headers, "x-chapa-signature");
        }
        String expected = Signatures.hmacHex("HmacSHA256", secret,
                rawBody == null ? new byte[0] : rawBody);
        if (!Signatures.matches(expected, given)) {
            throw Signatures.reject("Chapa", "signature did not match");
        }

        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw Signatures.reject("Chapa", "body was not readable JSON");
        }
        String reference = event.path("tx_ref").asString(null);
        if (reference == null) {
            reference = event.path("data").path("tx_ref").asString(null);
        }
        if (reference == null) {
            return Optional.empty();
        }
        // Verified rather than believed. The signature says Chapa sent it; the
        // verify call says what is actually true now.
        return poll(reference);
    }

    private static BigDecimal amountOf(JsonNode data) {
        try {
            return new BigDecimal(data.path("amount").asString("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
