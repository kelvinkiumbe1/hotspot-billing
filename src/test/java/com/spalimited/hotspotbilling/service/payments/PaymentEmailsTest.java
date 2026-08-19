package com.spalimited.hotspotbilling.service.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address given to a processor for a customer who has no email.
 *
 * <p>This was {@code @no-email.invalid} and Paystack rejected every one of
 * them, which meant no hotspot customer could ever pay by card. Found by
 * pointing the code at the real Paystack — the fake gateway accepted it,
 * because a stand-in written from documentation cannot know what the service
 * actually refuses.
 */
class PaymentEmailsTest {

    @Test
    @DisplayName("The customer's own address is used when they have one")
    void realAddressWins() {
        assertThat(PaymentEmails.forCustomer("ada@example.co.ke", "254712345678"))
                .isEqualTo("ada@example.co.ke");
    }

    @Test
    @DisplayName("A missing address becomes one a processor will accept")
    void derivedAddressIsAccepted() {
        String derived = PaymentEmails.forCustomer(null, "254712345678");

        assertThat(derived).isEqualTo("254712345678@no-reply.example.com");
        // The specific thing that broke: Paystack refuses the RFC 2606
        // never-resolvable TLD with "Invalid Email Address Passed", so every
        // card payment failed at the first request.
        assertThat(derived).doesNotEndWith(".invalid");
    }

    @Test
    @DisplayName("The placeholder domain can never belong to a stranger")
    void placeholderCannotBeRegistered() {
        // example.com is reserved by RFC 2606 §3, so a receipt sent there
        // reaches nobody -- which was the point of .invalid, kept.
        assertThat(PaymentEmails.forCustomer(null, "254712345678"))
                .endsWith("@no-reply.example.com");
    }

    @Test
    @DisplayName("Anything that is not an address at all is replaced")
    void rubbishIsReplaced() {
        assertThat(PaymentEmails.forCustomer("", "254712345678")).contains("@no-reply.");
        assertThat(PaymentEmails.forCustomer("not-an-email", "254712345678"))
                .contains("@no-reply.");
    }

    @Test
    @DisplayName("A number with punctuation still yields a valid local part")
    void punctuationIsStripped() {
        // "+254 712 345 678" as a local part is not a valid address, and the
        // processor's rejection would read as a card problem.
        assertThat(PaymentEmails.forCustomer(null, "+254 712 345 678"))
                .isEqualTo("254712345678@no-reply.example.com");
    }

    @Test
    @DisplayName("No number at all still produces something addressable")
    void noPhoneStillWorks() {
        assertThat(PaymentEmails.forCustomer(null, null))
                .isEqualTo("customer@no-reply.example.com");
    }
}
