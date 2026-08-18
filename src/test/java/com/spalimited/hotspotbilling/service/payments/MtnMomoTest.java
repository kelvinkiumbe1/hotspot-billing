package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.service.i18n.Country;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading MTN's answer, and working out which charge a callback is about.
 *
 * <p>The dangerous case here is not a crash. It is treating PENDING as "not
 * paid" — which fails a customer still typing their PIN — or believing an
 * unsigned callback body, which would let anyone who learned a reference mark
 * a payment successful.
 */
class MtnMomoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Optional<PaymentProvider.Settlement> read(String json) {
        return MtnMomoProvider.read(MAPPER.readTree(json), "ref-1");
    }

    @Test
    @DisplayName("A successful charge reports paid, with MTN's own transaction id")
    void successful() {
        var settlement = read("""
                {"amount":"100","currency":"GHS","externalId":"HOTSPOT-42",
                 "financialTransactionId":"1234567890","status":"SUCCESSFUL"}""");

        assertThat(settlement).isPresent();
        assertThat(settlement.get().paid()).isTrue();
        assertThat(settlement.get().reference()).isEqualTo("HOTSPOT-42");
        assertThat(settlement.get().receipt()).isEqualTo("1234567890");
        assertThat(settlement.get().amount()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("PENDING is not an answer, and must not read as a failure")
    void pendingIsNotAVerdict() {
        // The customer is still typing their PIN. Reporting this as unpaid
        // would cancel a sale that is seconds from completing.
        assertThat(read("""
                {"amount":"100","currency":"GHS","externalId":"X","status":"PENDING"}"""))
                .isEmpty();
    }

    @Test
    @DisplayName("A refusal reports why, from wherever MTN put the reason")
    void failedCarriesTheReason() {
        var nested = read("""
                {"amount":"50","currency":"GHS","externalId":"X","status":"FAILED",
                 "reason":{"code":"PAYER_LIMIT_REACHED","message":"Wallet limit reached"}}""");
        assertThat(nested).isPresent();
        assertThat(nested.get().paid()).isFalse();
        assertThat(nested.get().failureReason()).isEqualTo("Wallet limit reached");

        // Some markets state it flat rather than nested.
        var flat = read("""
                {"amount":"50","externalId":"X","status":"FAILED","reason":"NOT_ENOUGH_FUNDS"}""");
        assertThat(flat).isPresent();
        assertThat(flat.get().failureReason()).isEqualTo("NOT_ENOUGH_FUNDS");
    }

    @Test
    @DisplayName("Rejected and timed-out are refusals too, not unknowns")
    void otherTerminalStates() {
        for (String state : new String[]{"REJECTED", "TIMEOUT"}) {
            var settlement = read("{\"amount\":\"10\",\"externalId\":\"X\",\"status\":\"" + state + "\"}");
            assertThat(settlement).as("%s", state).isPresent();
            assertThat(settlement.get().paid()).as("%s", state).isFalse();
        }
    }

    @Test
    @DisplayName("An unreadable amount does not throw away the verdict")
    void badAmountStillSettles() {
        var settlement = read("""
                {"amount":"not-a-number","externalId":"X","status":"SUCCESSFUL"}""");

        // The status is the thing that matters; a malformed amount must not
        // turn a completed payment into an exception mid-sweep.
        assertThat(settlement).isPresent();
        assertThat(settlement.get().paid()).isTrue();
        assertThat(settlement.get().amount()).isEqualByComparingTo("0");
    }

    // --- Which charge is this callback about? ---

    @Test
    @DisplayName("The reference comes from the header when MTN sends one")
    void referenceFromHeader() {
        assertThat(MtnMomoProvider.referenceIn(
                "{}".getBytes(StandardCharsets.UTF_8),
                Map.of("X-Reference-Id", "abc-123")))
                .isEqualTo("abc-123");
    }

    @Test
    @DisplayName("Header matching ignores case, because gateways disagree about it")
    void referenceHeaderCaseInsensitive() {
        assertThat(MtnMomoProvider.referenceIn(
                new byte[0], Map.of("x-reference-id", "abc-123")))
                .isEqualTo("abc-123");
    }

    @Test
    @DisplayName("Failing that, it is read out of the body")
    void referenceFromBody() {
        String body = "{\"referenceId\":\"body-ref\",\"status\":\"SUCCESSFUL\"}";
        assertThat(MtnMomoProvider.referenceIn(body.getBytes(StandardCharsets.UTF_8), Map.of()))
                .isEqualTo("body-ref");
    }

    @Test
    @DisplayName("A callback with nothing to identify it is refused rather than guessed at")
    void noReferenceAtAll() {
        assertThat(MtnMomoProvider.referenceIn(
                "{\"status\":\"SUCCESSFUL\"}".getBytes(StandardCharsets.UTF_8), Map.of()))
                .isNull();
        assertThat(MtnMomoProvider.referenceIn(null, Map.of())).isNull();
    }

    // --- Which market ---

    @Test
    @DisplayName("Each MTN market has the name MTN itself uses")
    void targetEnvironments() {
        assertThat(MtnMomoProvider.targetFor(Country.GH)).isEqualTo("mtnghana");
        assertThat(MtnMomoProvider.targetFor(Country.UG)).isEqualTo("mtnuganda");
        assertThat(MtnMomoProvider.targetFor(Country.CI)).isEqualTo("mtnivorycoast");
    }

    @Test
    @DisplayName("A country MTN does not serve has no target, and is not invented")
    void unsupportedMarket() {
        // Kenya is M-Pesa's. Guessing a target here would send live charges
        // into a market the operator has no agreement with.
        assertThat(MtnMomoProvider.targetFor(Country.KE)).isNull();
        assertThat(MtnMomoProvider.targetFor(Country.NG)).isNull();
    }
}
