package com.spalimited.hotspotbilling.service.payments;

/**
 * An email address for a customer who does not have one.
 *
 * <p>Paystack, Flutterwave and Chapa all require an email. A hotspot customer
 * has a phone number and nothing else, and refusing the sale over a field
 * nobody will ever read would be the wrong trade — so one is derived.
 *
 * <p>It used to be derived onto {@code @no-email.invalid}. That reads as the
 * careful choice: {@code .invalid} is reserved by RFC 2606 precisely so it can
 * never resolve, which guarantees no stranger receives the receipt. Paystack
 * rejects it for exactly that reason — {@code Invalid Email Address Passed} —
 * so every hotspot customer paying by card, in every market, failed at the
 * first request.
 *
 * <p>Found by pointing the code at the real Paystack with a live test key. The
 * fake gateway had accepted it, because a stand-in written from documentation
 * validates what the documentation says and not what the service does. That is
 * the ceiling of that whole approach, demonstrated.
 *
 * <p>{@code example.com} is reserved by the same RFC, so it can never be
 * registered by anyone and the receipt still reaches nobody — but it is a
 * syntactically ordinary address that payment processors accept. Verified
 * against Paystack rather than assumed.
 */
final class PaymentEmails {

    /**
     * Reserved by RFC 2606 §3, so it cannot be registered and nothing sent here
     * can reach a real person. The {@code no-reply} subdomain says the same
     * thing to anybody reading a receipt.
     */
    private static final String PLACEHOLDER_DOMAIN = "@no-reply.example.com";

    private PaymentEmails() {
    }

    /**
     * The customer's own address where there is one, a derived placeholder
     * otherwise.
     *
     * <p>The {@code contains("@")} check is deliberately loose: a processor's
     * own validation is the authority, and rejecting something here that
     * Paystack would have accepted loses a sale for no reason.
     */
    static String forCustomer(String email, String phoneNumber) {
        if (email != null && email.contains("@")) {
            return email;
        }
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        // A local part is required. A customer with no number either is one of
        // the manual rails, which never reaches here.
        return (digits.isEmpty() ? "customer" : digits) + PLACEHOLDER_DOMAIN;
    }
}
