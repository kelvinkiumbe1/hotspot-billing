package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.service.i18n.Country;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Airtel's two traps.
 *
 * <p>The number format is the one that fails silently: Airtel identifies a
 * subscriber by their <em>national</em> number, and given the international form
 * it accepts the request and then cannot find them — a charge that looks sent
 * and never arrives. The status codes are the other: two letters, and treating
 * an ambiguous transaction as failed cancels a sale mid-PIN.
 */
class AirtelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Optional<PaymentProvider.Settlement> read(String json) {
        return AirtelProvider.read(MAPPER.readTree(json), "tx-1");
    }

    // --- The number format ---

    @Test
    @DisplayName("The dialling code comes off, because Airtel wants the national number")
    void stripsTheDiallingCode() {
        // Everything else in this system stores the full international form.
        // Sending that to Airtel is accepted and then finds no subscriber.
        assertThat(AirtelProvider.msisdn("254751234567", Country.KE)).isEqualTo("751234567");
        assertThat(AirtelProvider.msisdn("256772123456", Country.UG)).isEqualTo("772123456");
        assertThat(AirtelProvider.msisdn("265991234567", Country.MW)).isEqualTo("991234567");
    }

    @Test
    @DisplayName("A number typed with a plus or spaces still strips correctly")
    void toleratesFormatting() {
        assertThat(AirtelProvider.msisdn("+254 751 234 567", Country.KE)).isEqualTo("751234567");
        assertThat(AirtelProvider.msisdn("254-751-234-567", Country.KE)).isEqualTo("751234567");
    }

    @Test
    @DisplayName("A number already in national form is left alone")
    void leavesNationalAlone() {
        // Stripping twice would remove real digits from the front of the number.
        assertThat(AirtelProvider.msisdn("751234567", Country.KE)).isEqualTo("751234567");
    }

    @Test
    @DisplayName("Nigeria's longer numbers strip to ten digits, not nine")
    void nigeriaKeepsItsLength() {
        assertThat(AirtelProvider.msisdn("2348031234567", Country.NG)).isEqualTo("8031234567");
    }

    @Test
    @DisplayName("A null number does not become the string null")
    void nullStaysNull() {
        assertThat(AirtelProvider.msisdn(null, Country.KE)).isNull();
    }

    // --- The status codes ---

    @Test
    @DisplayName("TS is paid, and carries Airtel's own money id as the receipt")
    void transactionSuccess() {
        var settlement = read("""
                {"data":{"transaction":{"id":"tx-1","status":"TS",
                 "airtel_money_id":"MP210603.1234.L06941","message":"Success"}},
                 "status":{"code":"200","success":true}}""");

        assertThat(settlement).isPresent();
        assertThat(settlement.get().paid()).isTrue();
        assertThat(settlement.get().receipt()).isEqualTo("MP210603.1234.L06941");
    }

    @Test
    @DisplayName("TF is a refusal, and says why")
    void transactionFailed() {
        var settlement = read("""
                {"data":{"transaction":{"id":"tx-1","status":"TF",
                 "message":"Insufficient balance"}},"status":{"success":true}}""");

        assertThat(settlement).isPresent();
        assertThat(settlement.get().paid()).isFalse();
        assertThat(settlement.get().failureReason()).isEqualTo("Insufficient balance");
    }

    @Test
    @DisplayName("TA and TIP are not answers, and must not read as failures")
    void ambiguousAndInProgressArePending() {
        // The customer is still typing their PIN. Calling either a failure
        // cancels a sale that is seconds from completing.
        for (String state : new String[]{"TA", "TIP"}) {
            assertThat(read("{\"data\":{\"transaction\":{\"id\":\"tx-1\",\"status\":\""
                    + state + "\"}},\"status\":{\"success\":true}}"))
                    .as("%s", state).isEmpty();
        }
    }

    @Test
    @DisplayName("The callback field name differs from the enquiry field name")
    void callbackUsesStatusCode() {
        // Airtel calls it status on an enquiry and status_code on a callback.
        // Reading only one leaves half the outcomes invisible.
        var settlement = read("""
                {"data":{"transaction":{"id":"tx-1","status_code":"TS",
                 "airtel_money_id":"MP1"}},"status":{"success":true}}""");

        assertThat(settlement).isPresent();
        assertThat(settlement.get().paid()).isTrue();
    }

    // --- Whether the request was even taken ---

    @Test
    @DisplayName("Airtel wraps the outcome in a status object, so the HTTP code is not it")
    void acceptanceComesFromTheBody() {
        assertThat(AirtelProvider.accepted(MAPPER.readTree(
                "{\"status\":{\"code\":\"200\",\"success\":true}}"))).isTrue();
        assertThat(AirtelProvider.accepted(MAPPER.readTree(
                "{\"status\":{\"code\":\"200\",\"success\":false,\"message\":\"Invalid MSISDN\"}}")))
                .isFalse();
        assertThat(AirtelProvider.accepted(null)).isFalse();
    }

    @Test
    @DisplayName("A refusal message is passed through so the operator can act on it")
    void refusalMessage() {
        assertThat(AirtelProvider.message(MAPPER.readTree(
                "{\"status\":{\"success\":false,\"message\":\"Invalid MSISDN\"}}")))
                .isEqualTo("Invalid MSISDN");
        assertThat(AirtelProvider.message(null)).isEqualTo("no response");
    }

    // --- Which charge a callback is about ---

    @Test
    @DisplayName("The transaction id is read out of a callback body")
    void referenceFromBody() {
        String body = "{\"transaction\":{\"id\":\"tx-abc\",\"status_code\":\"TS\"}}";
        assertThat(AirtelProvider.referenceIn(body.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("tx-abc");
    }

    @Test
    @DisplayName("A callback with nothing to identify it is refused rather than guessed at")
    void noReference() {
        assertThat(AirtelProvider.referenceIn("{}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(AirtelProvider.referenceIn(null)).isNull();
    }
}
