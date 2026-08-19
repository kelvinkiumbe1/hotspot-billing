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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Paystack — cards, bank transfer and mobile money across Nigeria, Ghana, Kenya
 * and South Africa.
 *
 * <p>Two details decide whether this works, and both are easy to get wrong.
 * Amounts go in the currency's smallest unit, so a naira price is multiplied by
 * a hundred; sending the major unit undercharges by 99%. And webhooks are signed
 * with HMAC-SHA512 of the raw body using the same secret key used to
 * authenticate — not a separate webhook secret, unlike Stripe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaystackProvider implements PaymentProvider {

    // The address moved to PaymentEndpoints so a test can stand in front of it.
     // The default there is this same URL, so nothing changes in production.

    /**
     * Currencies Paystack quotes in whole units rather than hundredths. Every
     * other currency it supports is multiplied by 100; getting this list wrong
     * charges a customer a hundred times too much or too little, so it is
     * stated explicitly rather than guessed from the decimal setting.
     */
    /**
     * Currencies with no minor unit, which Paystack therefore quotes whole.
     *
     * <p>This list decides whether a customer is charged the right amount or a
     * hundred times it, and it held three currencies none of this system's
     * operators use while omitting every African one that qualifies. Paystack
     * runs in Cote d'Ivoire, which is XOF: a 150-franc pass was being sent as
     * 15,000. The same list on StripeProvider was already complete, which is
     * how the gap survived -- the two were never compared.
     *
     * <p>ISO 4217 zero-decimal, restricted to currencies this system knows
     * about plus the non-African ones already here.
     */
    private static final java.util.Set<String> ZERO_DECIMAL = java.util.Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG",
            "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

    private final PaymentGatewayService gateways;
    private final ObjectMapper mapper;
    private final PaymentEndpoints endpoints;

    /**
     * Built per call rather than once at construction.
     *
     * <p>The field initialiser froze the base URL before Spring had injected
     * anything, which is precisely why this conversation had never been
     * exercised. Every other rail in this package already builds its client per
     * call, so this is also the consistent shape.
     */
    private RestClient client() {
        return RestClient.create(endpoints.paystack());
    }

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.PAYSTACK;
    }

    @Override
    public boolean usable() {
        return secret() != null;
    }

    private String secret() {
        return gateways.find(PaymentGateway.Kind.PAYSTACK)
                .filter(PaymentGateway::isActive)
                .map(PaymentGateway::getSecretKey)
                .filter(k -> k != null && !k.isBlank())
                .orElse(null);
    }

    /**
     * The payment methods worth showing, ordered by what people there use.
     *
     * <p>Keyed on currency rather than the operator's country setting, because
     * Paystack itself only enables methods the merchant account is licensed
     * for — offering mobile money on a NGN account would simply be ignored,
     * while getting the order wrong on a GHS one costs real sales.
     *
     * <p>Empty means "let Paystack decide", which is the right answer for a
     * currency this does not know about.
     */
    static List<String> channelsFor(String currency) {
        if (currency == null) {
            return List.of();
        }
        return switch (currency.toUpperCase(java.util.Locale.ROOT)) {
            // Mobile money first; card kept as the fallback for the minority
            // who have one, rather than removed.
            case "GHS", "KES" -> List.of("mobile_money", "card");
            // Nigeria is the exception: bank transfer and USSD are what people
            // reach for, and mobile money barely registers.
            case "NGN" -> List.of("bank_transfer", "card", "ussd", "bank");
            case "ZAR" -> List.of("card", "eft");
            default -> List.of();
        };
    }

    @Override
    public Charge charge(ChargeRequest request) {
        String secret = secret();
        if (secret == null) {
            throw new IllegalStateException("Paystack is not configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // Paystack requires an email. A hotspot customer has a phone number and
        // nothing else, so one is derived from it rather than blocking the sale
        // — it only ever appears on the receipt Paystack sends itself.
        body.put("email", PaymentEmails.forCustomer(request.email(), request.phoneNumber()));
        body.put("amount", minorUnits(request.amount(), request.currency()));
        body.put("currency", request.currency());
        body.put("reference", request.reference());
        if (request.phoneNumber() != null) {
            body.put("metadata", Map.of("phone", request.phoneNumber(),
                    "description", request.description() == null ? "" : request.description()));
        }
        // Which methods to offer, and in what order.
        //
        // Left unset, Paystack leads with a card form. In Ghana or Kenya that
        // is the wrong first screen for almost everybody — mobile money is what
        // people actually use, and a customer who opens a page asking for a
        // 16-digit card number concludes they cannot pay and closes it.
        List<String> channels = channelsFor(request.currency());
        if (!channels.isEmpty()) {
            body.put("channels", channels);
        }

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
            log.warn("Paystack initialise failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the card payment. Please try again.");
        }
        if (response == null || !response.path("status").asBoolean(false)) {
            throw new IllegalStateException("Paystack refused the payment: "
                    + (response == null ? "no response" : response.path("message").asString("")));
        }
        JsonNode data = response.path("data");
        return new Charge(data.path("reference").asString(request.reference()),
                data.path("authorization_url").asString(null));
    }

    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String secret = secret();
        if (secret == null) {
            throw Signatures.reject("Paystack", "not configured");
        }
        String given = Signatures.header(headers, "x-paystack-signature");
        // Signed with the secret key itself — there is no separate webhook
        // secret to forget to configure, which is one fewer way to be insecure.
        String expected = Signatures.hmacHex("HmacSHA512", secret,
                rawBody == null ? new byte[0] : rawBody);
        if (!Signatures.matches(expected, given)) {
            throw Signatures.reject("Paystack", "signature did not match");
        }

        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw Signatures.reject("Paystack", "body was not readable JSON");
        }
        String type = event.path("event").asString("");
        JsonNode data = event.path("data");
        String reference = data.path("reference").asString(null);

        // One endpoint carries every event type. A refund or a transfer is
        // authentic but is not a purchase outcome, and treating an unknown
        // event as a failure would mark good payments failed.
        return switch (type) {
            case "charge.success" -> Optional.of(new Settlement(
                    reference, reference, true,
                    majorUnits(data.path("amount").asLong(0), data.path("currency").asString("KES")),
                    data.path("currency").asString(null),
                    reference, null));
            case "charge.failed" -> Optional.of(new Settlement(
                    reference, reference, false, null, null, null,
                    data.path("gateway_response").asString("the card was declined")));
            default -> {
                log.debug("Paystack event {} needs no action", type);
                yield Optional.empty();
            }
        };
    }

    static long minorUnits(BigDecimal amount, String currency) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        if (ZERO_DECIMAL.contains(currency == null ? "" : currency.toUpperCase())) {
            return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static BigDecimal majorUnits(long minor, String currency) {
        if (ZERO_DECIMAL.contains(currency == null ? "" : currency.toUpperCase())) {
            return BigDecimal.valueOf(minor);
        }
        return BigDecimal.valueOf(minor).movePointLeft(2);
    }

    // --- Recurring ---

    @Override
    public boolean supportsRecurring() {
        return true;
    }

    /**
     * Paystack hands back an authorization code on every successful charge.
     *
     * <p>Only some of them can be used again, and the flag saying so is the
     * whole check. A one-time bank transfer or a USSD payment produces an
     * authorization that looks identical and is not reusable; storing it gives
     * an operator a mandate that fails on its first renewal, by which point they
     * have stopped chasing the customer.
     */
    @Override
    public Optional<String> reusableToken(byte[] rawBody) {
        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            return Optional.empty();
        }
        JsonNode auth = event.path("data").path("authorization");
        if (!auth.path("reusable").asBoolean(false)) {
            return Optional.empty();
        }
        String code = auth.path("authorization_code").asString(null);
        return code == null || code.isBlank() ? Optional.empty() : Optional.of(code);
    }

    /**
     * Charges a stored authorization. The customer is not there.
     *
     * <p>Paystack answers in the response rather than by webhook for this call,
     * so the verdict is read here — but a webhook follows for the same
     * reference, and settling twice is prevented by the payment no longer being
     * PENDING.
     */
    @Override
    public Charge chargeStored(String token, ChargeRequest request) {
        String secret = secret();
        if (secret == null) {
            throw new IllegalStateException("Paystack is not set up");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorization_code", token);
        body.put("email", PaymentEmails.forCustomer(request.email(), request.phoneNumber()));
        body.put("amount", minorUnits(request.amount(), request.currency()));
        body.put("currency", request.currency());
        body.put("reference", request.reference());

        JsonNode response;
        try {
            response = client().post()
                    .uri("/transaction/charge_authorization")
                    .header("Authorization", "Bearer " + secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("Paystack would not charge the saved method: "
                    + e.getMessage());
        }
        if (response == null || !response.path("status").asBoolean(false)) {
            throw new IllegalStateException("Paystack refused the renewal: "
                    + (response == null ? "no response" : response.path("message").asString("")));
        }
        JsonNode data = response.path("data");
        String status = data.path("status").asString("");
        if (!"success".equalsIgnoreCase(status)) {
            // "failed" and "abandoned" both arrive here with status:true at the
            // envelope level -- Paystack is reporting that it successfully told
            // us the charge did not work.
            throw new IllegalStateException("The saved payment method was declined: "
                    + data.path("gateway_response").asString(status));
        }
        // No URL: there is nobody to send anywhere.
        return new Charge(data.path("reference").asString(request.reference()), null);
    }
}
