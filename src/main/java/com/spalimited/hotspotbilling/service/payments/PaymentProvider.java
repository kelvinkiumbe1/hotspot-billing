package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * One way of taking money.
 *
 * <p>Two shapes have to fit behind this, and they are genuinely different.
 * M-Pesa pushes a prompt to the customer's handset and calls back when they
 * enter their PIN — nothing to open, nothing to redirect to. Paystack,
 * Flutterwave and Stripe hand back a URL the customer opens, pay there, and the
 * result arrives by webhook. So a charge may or may not produce somewhere to
 * send the customer, and callers must cope with both.
 *
 * <p>What is the same is the ending: a webhook that says a reference was paid.
 * Everything downstream — issuing the voucher, texting the code, loyalty,
 * reconciliation — already funnels through one place and does not need to know
 * which rail was used.
 */
public interface PaymentProvider {

    PaymentGateway.Kind kind();

    /** Whether this rail could actually take a payment right now. */
    boolean usable();

    /** What the customer is buying, and who is buying it. */
    record ChargeRequest(String phoneNumber, String email, BigDecimal amount,
                         String currency, String reference, String description) {
    }

    /**
     * A started payment. {@code providerRef} is what the webhook will quote
     * back; {@code checkoutUrl} is null for rails that prompt the phone
     * directly.
     */
    record Charge(String providerRef, String checkoutUrl) {
    }

    /**
     * What a verified webhook says happened. {@code reference} is ours,
     * {@code providerRef} is theirs — either may be the one we can match on,
     * so both are carried.
     */
    record Settlement(String providerRef, String reference, boolean paid,
                      BigDecimal amount, String currency, String receipt, String failureReason) {
    }

    /**
     * Starts a payment. Throws when the rail is unusable or refuses, because a
     * customer who pressed Pay needs to be told rather than left waiting for a
     * callback that will never come.
     */
    Charge charge(ChargeRequest request);

    /**
     * Verifies an inbound webhook and says what it settles.
     *
     * <p>Takes the body as received. Every one of these providers signs the
     * exact bytes, so a parsed-and-reserialised copy would fail verification —
     * the same trap as Meta's webhook.
     *
     * <p>Returns empty when the payload is authentic but is not a payment
     * outcome we act on: providers send many event types down one endpoint, and
     * a charge.refunded is not a failed purchase. An unauthentic payload throws.
     */
    Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers);
}
