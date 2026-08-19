package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.PaymentService;
import com.spalimited.hotspotbilling.service.payments.FlutterwaveProvider;
import com.spalimited.hotspotbilling.service.payments.AirtelProvider;
import com.spalimited.hotspotbilling.service.payments.ChapaProvider;
import com.spalimited.hotspotbilling.service.payments.MtnMomoProvider;
import com.spalimited.hotspotbilling.service.payments.OrangeMoneyProvider;
import com.spalimited.hotspotbilling.service.payments.PaynowProvider;
import com.spalimited.hotspotbilling.service.payments.MandateService;
import com.spalimited.hotspotbilling.service.payments.PaymentProvider;
import com.spalimited.hotspotbilling.service.payments.ChargilyProvider;
import com.spalimited.hotspotbilling.service.payments.DpoProvider;
import com.spalimited.hotspotbilling.service.payments.KonnectProvider;
import com.spalimited.hotspotbilling.service.payments.MulticaixaProvider;
import com.spalimited.hotspotbilling.service.payments.PaymobProvider;
import com.spalimited.hotspotbilling.service.payments.PaystackProvider;
import com.spalimited.hotspotbilling.service.payments.Signatures;
import com.spalimited.hotspotbilling.service.payments.StripeProvider;
import com.spalimited.hotspotbilling.service.payments.WaveProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final MtnMomoProvider mtnMomo;
    private final ChapaProvider chapa;
    private final PaynowProvider paynow;
    private final AirtelProvider airtel;
    private final FlutterwaveProvider flutterwave;
    private final StripeProvider stripe;
    private final OrangeMoneyProvider orangeMoney;
    private final WaveProvider wave;
    private final PaymobProvider paymob;
    private final KonnectProvider konnect;
    private final MulticaixaProvider multicaixa;
    private final ChargilyProvider chargily;
    private final DpoProvider dpo;
    private final PaymentService payments;
    private final MandateService mandates;

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
     * MTN MoMo, which signs nothing.
     *
     * <p>The body is read only far enough to learn which charge it concerns;
     * the provider then asks MTN directly. Trusting the body would let anyone
     * who learned a reference mark a payment successful, so this endpoint is
     * safe to leave open in a way the signed ones would not be.
     */
    @PostMapping("/mtn-momo/webhook")
    public ResponseEntity<String> mtnMomo(@RequestBody(required = false) byte[] body,
                                          HttpServletRequest request) {
        return handle("MTN MoMo", mtnMomo, body, request);
    }

    /** Chapa — Ethiopia. Signed, and verified before anything is read. */
    /**
     * Paymob's transaction callback.
     *
     * <p>Signed, but not over the body: Paymob hashes twenty named fields in a
     * fixed order, so the provider does the verifying rather than a shared
     * helper. The raw bytes are still what is passed in, because the fields are
     * read out of them and a reserialised copy could reorder anything.
     */
    @PostMapping("/paymob/webhook")
    public ResponseEntity<String> paymob(@RequestBody(required = false) byte[] body,
                                         HttpServletRequest request) {
        return handle("Paymob", paymob, body, request);
    }

    /**
     * Konnect's callback, which arrives as a GET.
     *
     * <p>The only one here that is not a POST, and it carries a payment
     * reference in the query string rather than a body. There is nothing to
     * verify — Konnect does not sign it — so this goes straight to asking
     * Konnect what happened, which is the same thing {@code settle} would do
     * with a body and the same thing the sweep does an hour later.
     */
    @GetMapping("/konnect/webhook")
    public ResponseEntity<String> konnectCallback(
            @RequestParam(name = "payment_ref", required = false) String paymentRef) {
        if (paymentRef == null || paymentRef.isBlank()) {
            // Not an error worth a 500: Konnect retries, and a bare GET with no
            // reference is as likely to be a health check as a lost payment.
            return ResponseEntity.ok("ignored");
        }
        return finish("Konnect", konnect.poll(paymentRef));
    }

    /** The same, for a merchant account configured to POST instead. */
    @PostMapping("/konnect/webhook")
    public ResponseEntity<String> konnect(@RequestBody(required = false) byte[] body,
                                          HttpServletRequest request) {
        return handle("Konnect", konnect, body, request);
    }

    /**
     * EMIS's callback for a Multicaixa Express payment.
     *
     * <p>Unsigned, so it is read only for the frame id and the verdict comes from
     * asking EMIS -- the same treatment MTN's callback gets, and for the same
     * reason.
     */
    @PostMapping("/multicaixa/webhook")
    public ResponseEntity<String> multicaixa(@RequestBody(required = false) byte[] body,
                                             HttpServletRequest request) {
        return handle("Multicaixa Express", multicaixa, body, request);
    }

    /**
     * Chargily's webhook, which is signed properly.
     *
     * <p>HMAC-SHA256 over the exact bytes, so this one is believed rather than
     * treated as a hint -- the same footing as Stripe and Wave.
     */
    @PostMapping("/chargily/webhook")
    public ResponseEntity<String> chargily(@RequestBody(required = false) byte[] body,
                                           HttpServletRequest request) {
        return handle("Chargily", chargily, body, request);
    }

    /**
     * DPO's payment notification.
     *
     * <p>Unsigned, and configured in DPO's dashboard rather than in our request,
     * so it is read only for the transaction token and the verdict comes from
     * verifyToken -- the same treatment MTN's callback gets.
     */
    @PostMapping("/dpo/webhook")
    public ResponseEntity<String> dpo(@RequestBody(required = false) byte[] body,
                                      HttpServletRequest request) {
        return handle("DPO", dpo, body, request);
    }

    @PostMapping("/chapa/webhook")
    public ResponseEntity<String> chapa(@RequestBody(required = false) byte[] body,
                                        HttpServletRequest request) {
        return handle("Chapa", chapa, body, request);
    }

    /**
     * Paynow — Zimbabwe. Hashed rather than signed, but genuinely verifiable:
     * SHA-512 over the values in Paynow's own order, salted with the
     * integration key.
     */
    @PostMapping("/paynow/webhook")
    public ResponseEntity<String> paynow(@RequestBody(required = false) byte[] body,
                                         HttpServletRequest request) {
        return handle("Paynow", paynow, body, request);
    }

    /**
     * Airtel Money. Its optional hash varies by market, so the body is read
     * only far enough to find the transaction id and the verdict comes from an
     * enquiry — an unverified body must never be able to mark a payment paid.
     */
    @PostMapping("/airtel/webhook")
    public ResponseEntity<String> airtel(@RequestBody(required = false) byte[] body,
                                         HttpServletRequest request) {
        return handle("Airtel", airtel, body, request);
    }

    /**
     * Orange Money, which needs a lookup before it can be asked anything.
     *
     * <p>Its notification quotes the order id and nothing else this can use. The
     * status query needs the pay token as well, and the token cannot be carried
     * in the notification URL because Orange only issues it in the reply to the
     * request that sets that URL. So the order id becomes the stored reference,
     * the stored reference becomes a question for Orange, and Orange's answer is
     * the verdict. Nothing in the body is believed at any point.
     */
    @PostMapping("/orange-money/webhook")
    public ResponseEntity<String> orangeMoney(@RequestBody(required = false) byte[] body) {
        String orderId = OrangeMoneyProvider.notifiedOrderId(body);
        if (orderId == null || orderId.isBlank()) {
            throw Signatures.reject("Orange Money", "no order id in the notification");
        }
        String stored = payments
                .providerRefStartingWith(OrangeMoneyProvider.refPrefix(orderId))
                .orElse(null);
        if (stored == null) {
            // Genuine notification for something we have no record of. Nothing
            // to do and nothing to retry, so acknowledge it rather than earning
            // an escalating stream of redeliveries.
            log.warn("Orange Money notified about unknown order {}", orderId);
            return ResponseEntity.ok("unknown");
        }
        return finish("Orange Money", orangeMoney.poll(stored));
    }

    /**
     * Wave, which signs properly — {@code Wave-Signature: t=…,v1=…} over the raw
     * body, the same scheme as Stripe. The only wallet rail here whose body can
     * be believed without a second round trip.
     */
    @PostMapping("/wave/webhook")
    public ResponseEntity<String> wave(@RequestBody(required = false) byte[] body,
                                       HttpServletRequest request) {
        return handle("Wave", wave, body, request);
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
        ResponseEntity<String> result = finish(name, settlement);

        // A completed payment may also be the moment a customer authorises
        // renewals. Read after settling, never before: a token stored against a
        // payment that then failed is an authorisation for money that never
        // moved. Only the rails that can charge again are even asked.
        if (settlement.isPresent() && settlement.get().paid() && provider.supportsRecurring()) {
            try {
                provider.reusableToken(body).ifPresent(token ->
                        mandates.captureToken(settlement.get().reference(), token));
            } catch (Exception e) {
                // The payment is settled and the customer is online. Failing to
                // store the token costs them a prompt next month; failing the
                // webhook here would cost them their voucher.
                log.warn("Could not store the renewal authorisation from {}: {}",
                        name, e.getMessage());
            }
        }
        return result;
    }

    /** The half after verification, shared with the rails that verify their own way. */
    private ResponseEntity<String> finish(String name,
                                          java.util.Optional<PaymentProvider.Settlement> settlement) {
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
