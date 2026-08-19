package com.spalimited.hotspotbilling.service.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Squeezing our references into Vodacom's alphabet without losing any.
 *
 * <p>Vodacom takes twenty alphanumeric characters and ours are {@code HS-31}
 * and {@code PPPOE-4-88}, so they have to be rewritten. The obvious rewrite —
 * delete the punctuation — is wrong in a way that is invisible until it costs
 * somebody a payment, and this is the file that says why.
 */
class VodacomReferenceTest {

    @Test
    @DisplayName("Two references that differ still differ afterwards")
    void referencesThatWouldCollideDoNot() {
        // Subscription 4, payment 88. And subscription 48, payment 8. Two
        // different customers, two different payments -- and both become
        // PPPOE488 the moment the hyphens are simply dropped. Vodacom would
        // refuse the second as a duplicate, or answer a question about one of
        // them with the other one's outcome.
        String a = VodacomMpesaProvider.reference("PPPOE-4-88");
        String b = VodacomMpesaProvider.reference("PPPOE-48-8");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("No two of the references this system generates collide")
    void theReferencesWeActuallyGenerateAreDistinct() {
        // Both shapes, across the ranges of ids that produce collisions under
        // naive stripping. A hand-picked pair proves the case; this proves there
        // is no other pair.
        Set<String> seen = new HashSet<>();
        for (int payment = 1; payment <= 200; payment++) {
            assertThat(seen.add(VodacomMpesaProvider.reference("HS-" + payment))).isTrue();
            for (int sub = 1; sub <= 200; sub++) {
                String ours = "PPPOE-" + sub + "-" + payment;
                assertThat(seen.add(VodacomMpesaProvider.reference(ours)))
                        .as("%s collided with a reference already issued", ours)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("What Vodacom accepts is all that comes out")
    void theResultIsAlphanumericAndShortEnough() {
        for (String ours : new String[]{"HS-31", "PPPOE-4-88", "HS_9", "HS 12", "HS/3",
                "PPPOE-999999-1234567", "A-VERY-LONG-REFERENCE-FROM-SOMEWHERE-ELSE-1"}) {
            String out = VodacomMpesaProvider.reference(ours);
            assertThat(out)
                    .as("%s became %s", ours, out)
                    .matches("[A-Za-z0-9]{1,20}");
        }
    }

    @Test
    @DisplayName("An over-long reference keeps the end, where the id is")
    void truncationKeepsTheUniquePart() {
        // The payment id is at the end of every reference this system makes, so
        // trimming the front is what keeps two long references apart. Trimming
        // the back would make every long PPPOE reference identical.
        String a = VodacomMpesaProvider.reference("PPPOE-123456789012-345");
        String b = VodacomMpesaProvider.reference("PPPOE-123456789012-346");

        assertThat(a).hasSize(20).isNotEqualTo(b);
        assertThat(a).endsWith("345");
    }

    @Test
    @DisplayName("A reference we somehow do not have still produces one")
    void aMissingReferenceDoesNotProduceABlank() {
        // Vodacom rejects an empty transaction reference outright, which would
        // fail the charge rather than degrade it.
        assertThat(VodacomMpesaProvider.reference(null)).matches("[A-Za-z0-9]{20}");
        assertThat(VodacomMpesaProvider.reference("  ")).matches("[A-Za-z0-9]{20}");
    }
}
