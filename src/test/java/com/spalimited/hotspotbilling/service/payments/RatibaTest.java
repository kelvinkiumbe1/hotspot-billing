package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentMandate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things about Ratiba most likely to be wrong.
 *
 * <p>Both are bare strings on the wire with no validation worth the name. A
 * date in the wrong format or a frequency code off by one is accepted, and the
 * customer is then debited weekly for a monthly service — or never at all, and
 * the operator has stopped chasing them.
 */
class RatibaTest {

    private static PaymentMandate mandate(PaymentMandate.Frequency frequency) {
        return PaymentMandate.builder()
                .subscriberId(42L)
                .amount(new BigDecimal("2500.00"))
                .frequency(frequency)
                .startsOn(LocalDate.of(2026, 9, 5))
                .build();
    }

    @Test
    @DisplayName("Dates go as yyyyMMdd with no separators")
    void dateFormat() {
        assertThat(Ratiba.date(LocalDate.of(2026, 9, 5))).isEqualTo("20260905");
        // Single-digit months and days are padded, not shortened.
        assertThat(Ratiba.date(LocalDate.of(2026, 1, 1))).isEqualTo("20260101");
        assertThat(Ratiba.date(LocalDate.of(2026, 12, 31))).isEqualTo("20261231");
    }

    @Test
    @DisplayName("Each frequency has Safaricom's own code, not an ordinal")
    void frequencyCodes() {
        // Derived from an enum ordinal, reordering the enum later would silently
        // change every customer's billing period.
        assertThat(Ratiba.frequencyCode(PaymentMandate.Frequency.WEEKLY)).isEqualTo("3");
        assertThat(Ratiba.frequencyCode(PaymentMandate.Frequency.MONTHLY)).isEqualTo("4");
        assertThat(Ratiba.frequencyCode(PaymentMandate.Frequency.QUARTERLY)).isEqualTo("6");
        assertThat(Ratiba.frequencyCode(PaymentMandate.Frequency.YEARLY)).isEqualTo("8");
    }

    @Test
    @DisplayName("A paybill request says paybill in both places it has to")
    void paybillRequest() {
        Map<String, Object> body = Ratiba.request(mandate(PaymentMandate.Frequency.MONTHLY),
                "254712345678", "174379", false, "254712345678", "https://x/cb");

        // The transaction type and the identifier type have to agree. One right
        // and the other wrong is refused with a message that does not say which.
        assertThat(body.get("TransactionType")).isEqualTo("Standing Order Customer Pay Bill");
        assertThat(body.get("ReceiverPartyIdentifierType")).isEqualTo("4");
        assertThat(body.get("BusinessShortCode")).isEqualTo("174379");
        assertThat(body.get("Frequency")).isEqualTo("4");
        assertThat(body.get("PartyA")).isEqualTo("254712345678");
    }

    @Test
    @DisplayName("A till request says till in both places")
    void tillRequest() {
        Map<String, Object> body = Ratiba.request(mandate(PaymentMandate.Frequency.MONTHLY),
                "254712345678", "846123", true, "254712345678", "https://x/cb");

        assertThat(body.get("TransactionType")).isEqualTo("Standing Order Customer Pay Marchant");
        assertThat(body.get("ReceiverPartyIdentifierType")).isEqualTo("2");
    }

    @Test
    @DisplayName("The amount is whole units, not minor units")
    void amountIsMajorUnits() {
        Map<String, Object> body = Ratiba.request(mandate(PaymentMandate.Frequency.MONTHLY),
                "254712345678", "174379", false, "ref", "https://x/cb");

        // 2500 shillings. Sending 250000 the way a card rail wants would debit
        // a hundred times the subscription every month.
        assertThat(body.get("Amount")).isEqualTo("2500");
    }

    @Test
    @DisplayName("An open-ended mandate gets a far-future end date rather than none")
    void openEndedGetsAnEndDate() {
        Map<String, Object> body = Ratiba.request(mandate(PaymentMandate.Frequency.MONTHLY),
                "254712345678", "174379", false, "ref", "https://x/cb");

        // Safaricom requires one. Omitting it, or sending next month, silently
        // ends the standing order while the customer is still a customer.
        assertThat(body.get("StartDate")).isEqualTo("20260905");
        assertThat(body.get("EndDate")).isEqualTo("20360905");
    }

    @Test
    @DisplayName("An explicit end date is honoured")
    void explicitEndDate() {
        PaymentMandate fixed = mandate(PaymentMandate.Frequency.MONTHLY);
        fixed.setEndsOn(LocalDate.of(2027, 3, 1));

        assertThat(Ratiba.request(fixed, "254712345678", "174379", false, "ref", "https://x/cb")
                .get("EndDate")).isEqualTo("20270301");
    }

    @Test
    @DisplayName("The name is unique per mandate and rebuildable from what is stored")
    void nameIsAStableHandle() {
        // Safaricom gives back no id of its own, so the name we send is the only
        // handle there will ever be.
        String name = Ratiba.standingOrderName(42L, LocalDate.of(2026, 9, 5));
        assertThat(name).isEqualTo("SUB42-20260905");
        assertThat(Ratiba.standingOrderName(42L, LocalDate.of(2026, 9, 5))).isEqualTo(name);
        assertThat(Ratiba.standingOrderName(43L, LocalDate.of(2026, 9, 5))).isNotEqualTo(name);
    }

    // --- The state machine, which decides whether a customer is chased ---

    @Test
    @DisplayName("Only an active mandate is a reason to stop chasing")
    void onlyActiveStopsTheChase() {
        PaymentMandate m = mandate(PaymentMandate.Frequency.MONTHLY);

        // Ratiba needs the customer to approve on their handset, which is
        // neither instant nor guaranteed. Treating PENDING as collecting means
        // a customer who never approved is never chased and quietly lapses.
        m.setStatus(PaymentMandate.Status.PENDING);
        assertThat(m.isCollecting()).isFalse();

        m.setStatus(PaymentMandate.Status.ACTIVE);
        assertThat(m.isCollecting()).isTrue();

        m.setStatus(PaymentMandate.Status.FAILED);
        assertThat(m.isCollecting()).isFalse();

        m.setStatus(PaymentMandate.Status.CANCELLED);
        assertThat(m.isCollecting()).isFalse();
    }

    @Test
    @DisplayName("A mandate that claims to be live and never collected is flagged")
    void suspectMandates() {
        PaymentMandate stale = mandate(PaymentMandate.Frequency.MONTHLY);
        stale.setStatus(PaymentMandate.Status.ACTIVE);
        stale.setStartsOn(LocalDate.now().minusDays(60));
        stale.setCollections(0);

        // The dangerous state: the operator stopped chasing on the strength of
        // this, and the first sign otherwise is the customer lapsing.
        assertThat(stale.isSuspect()).isTrue();

        stale.setCollections(2);
        assertThat(stale.isSuspect()).isFalse();

        PaymentMandate fresh = mandate(PaymentMandate.Frequency.MONTHLY);
        fresh.setStatus(PaymentMandate.Status.ACTIVE);
        fresh.setStartsOn(LocalDate.now().minusDays(3));
        // Too new to have collected yet, so not suspicious.
        assertThat(fresh.isSuspect()).isFalse();
    }
}
