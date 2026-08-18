package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.service.i18n.Country;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The currency the price is written in has to be the currency it is collected
 * in.
 *
 * <p>Three rails take the amount as a bare number in their market's currency,
 * and the operator's price currency is a separate setting from their country.
 * The admin fills one in from the other, but the field stays editable and the
 * API accepts either alone — so they can disagree, and when they do a plan
 * priced 1000 and shown as KES 1,000 reaches Wave as 1000 XOF.
 */
class MarketGuardTest {

    @Test
    @DisplayName("Matching currencies are fine")
    void matching() {
        assertThat(MarketGuard.currencyAgrees("Wave", Country.SN, "XOF")).isTrue();
        assertThat(MarketGuard.currencyAgrees("Airtel Money", Country.KE, "KES")).isTrue();
        // Case and whitespace are an operator typing, not a disagreement.
        assertThat(MarketGuard.currencyAgrees("Wave", Country.SN, " xof ")).isTrue();
    }

    @Test
    @DisplayName("A mismatch is refused rather than silently converted")
    void mismatch() {
        // The scenario: a Kenyan install sets country to Senegal through the API
        // and never touches the currency, which is still KES from the default.
        assertThat(MarketGuard.currencyAgrees("Wave", Country.SN, "KES")).isFalse();
        assertThat(MarketGuard.currencyAgrees("Orange Money", Country.CI, "USD")).isFalse();
    }

    @Test
    @DisplayName("No configured currency is a fresh install, not a contradiction")
    void unsetPasses() {
        assertThat(MarketGuard.currencyAgrees("Wave", Country.SN, null)).isTrue();
        assertThat(MarketGuard.currencyAgrees("Wave", Country.SN, "")).isTrue();
    }

    @Test
    @DisplayName("Countries that share a currency agree with each other")
    void sharedCurrency() {
        // XOF covers Senegal and Cote d'Ivoire, XAF covers Cameroon. An operator
        // in one XOF country is not misconfigured for having XOF.
        assertThat(Country.SN.currency()).isEqualTo(Country.CI.currency());
        assertThat(MarketGuard.currencyAgrees("Orange Money", Country.CI, "XOF")).isTrue();
        assertThat(MarketGuard.currencyAgrees("Orange Money", Country.CM, "XOF")).isFalse();
    }
}
