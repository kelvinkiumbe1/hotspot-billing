package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.PaymentService;
import com.spalimited.hotspotbilling.service.payments.FlutterwaveProvider;
import com.spalimited.hotspotbilling.service.payments.PaymentProvider;
import com.spalimited.hotspotbilling.service.payments.PaystackProvider;
import com.spalimited.hotspotbilling.service.payments.StripeProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the card processors tell us a payment happened.
 *
 * <p>Each endpoint verifies the delivery before believing a word of it. These
 * are public URLs that mint vouchers, so an unverified one is a free-internet
 * generator for anyone who finds the address.
 *
 * <p>The body is taken as raw bytes. Every one of these providers signs the
 * exact bytes it sent, so parsing and re-serialising would break verification —
 * the same trap as Meta's webhook, and worth stating at each of the four places
 * it applies.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class ProviderWebhookController {

    private final PaystackProvider paystack;
    private final FlutterwaveProvider flutterwave;
    private final StripeProvider stripe;
    private final PaymentService payments;

    @PostMapping("/paystack/webhook")
    public ResponseEntity<String> paystack(@RequestBody(required = false) byte[] body,
                                           HttpServletRequest request) {
        return handle("Paystack", paystack, body, request);
    }

    @PostMapping("/flutterwave/webhook")
    public ResponseEntity<String> flutterwave(@RequestBody(required = false) byte[] body,
                                              HttpServletRequest request) {
        return handle("Flutterwave", flutterwave, body, request);
    }

    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> stripe(@RequestBody(required = false) byte[] body,
                                         HttpServletRequest request) {
        return handle("Stripe", stripe, body, request);
    }

    /**
     * Verify, settle, acknowledge.
     *
     * <p>A verified delivery is always acknowledged with 200, even when we do
     * nothing with it: all three retry on anything else, so returning an error
     * for an event we simply do not act on earns an escalating stream of
     * redeliveries of a message that was never a problem.
     *
     * <p>A delivery that fails verification is not acknowledged — it gets the
     * 403 the provider's own check produced, because pretending to accept a
     * forgery hides an attack in progress.
     */
    private ResponseEntity<String> handle(String name, PaymentProvider provider,
                                          byte[] body, HttpServletRequest request) {
        var settlement = provider.settle(body, headersOf(request));
        if (settlement.isEmpty()) {
            return ResponseEntity.ok("ignored");
        }
        PaymentProvider.Settlement s = settlement.get();
        try {
            payments.settleFromProvider(name, s.providerRef(), s.reference(), s.paid(),
                    s.amount(), s.receipt(), s.failureReason());
        } catch (Exception e) {
            // The delivery was genuine; something on our side failed. Say so
            // loudly and let the provider retry, which is exactly what its
            // redelivery is for.
            log.error("Could not settle a verified {} webhook for {}: {}",
                    name, s.reference(), e.getMessage());
            return ResponseEntity.internalServerError().body("retry");
        }
        return ResponseEntity.ok("ok");
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            out.put(name, request.getHeader(name));
        }
        return out;
    }
}
