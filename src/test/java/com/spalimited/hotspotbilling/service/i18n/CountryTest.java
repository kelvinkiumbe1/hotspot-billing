package com.spalimited.hotspotbilling.service.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a country implies about how its customers pay.
 *
 * <p>The bug this guards against shipped last week: "M-Pesa" was written into
 * the portal seventy-eight times, including inside the French and Portuguese
 * translations, so a Ghanaian operator's customers would have been asked to
 * pay with something that does not exist in Ghana.
 */
class CountryTest {

    @Test
    @DisplayName("Every country says something a customer there would recognise")
    void everyCountryNamesSomethingReal() {
        for (Country country : Country.values()) {
            assertThat(country.paymentBrand()).as("%s", country).isNotBlank();
            assertThat(country.networks()).as("%s", country).isNotEmpty();
            assertThat(country.currency()).as("%s", country).hasSize(3);
            // Falls back to English rather than to nothing, and the language
            // must be one we can actually serve.
            assertThat(Language.of(country.language()).code())
                    .as("%s asks for a language we do not have", country)
                    .isEqualTo(country.language());
        }
    }

    @Test
    @DisplayName("Only Kenya and Mozambique lead with M-Pesa")
    void mpesaIsNotUniversal() {
        assertThat(Country.KE.paymentBrand()).isEqualTo("M-Pesa");
        assertThat(Country.MZ.paymentBrand()).isEqualTo("M-Pesa");
        // The whole point: everywhere else, saying "M-Pesa" is wrong.
        assertThat(Country.GH.paymentBrand()).isEqualTo("MTN MoMo");
        assertThat(Country.SN.paymentBrand()).isEqualTo("Orange Money");
        assertThat(Country.ET.paymentBrand()).isEqualTo("Telebirr");
    }

    @Test
    @DisplayName("Nigeria and South Africa are the card exceptions, and say so")
    void cardMarketsDoNotClaimMobileMoney() {
        assertThat(Country.NG.paymentBrand()).doesNotContain("MoMo").contains("bank transfer");
        assertThat(Country.ZA.paymentBrand()).contains("card");
        assertThat(Country.NG.networks()).contains("Bank transfer");
    }

    @Test
    @DisplayName("Countries no built gateway reaches are flagged, not quietly broken")
    void unreachableCountriesAreNamed() {
        // Telebirr, EcoCash and Multicaixa are domestic systems that Paystack,
        // Flutterwave and Stripe do not touch. An operator there needs to know
        // before they launch, not after their first customer cannot pay.
        assertThat(Country.ET.needsManualCollection()).isTrue();
        assertThat(Country.ZW.needsManualCollection()).isTrue();
        assertThat(Country.AO.needsManualCollection()).isTrue();

        assertThat(Country.KE.needsManualCollection()).isFalse();
        assertThat(Country.GH.needsManualCollection()).isFalse();
    }

    @Test
    @DisplayName("Francophone countries default to French, Lusophone to Portuguese")
    void languageFollowsTheCountry() {
        assertThat(Country.CI.language()).isEqualTo("fr");
        assertThat(Country.SN.language()).isEqualTo("fr");
        assertThat(Country.CM.language()).isEqualTo("fr");
        assertThat(Country.MZ.language()).isEqualTo("pt");
        assertThat(Country.AO.language()).isEqualTo("pt");
        assertThat(Country.TZ.language()).isEqualTo("sw");
    }

    @Test
    @DisplayName("An unknown country is 'somewhere else', not Kenya")
    void unknownFallsBackHonestly() {
        // Defaulting a Peruvian operator to Kenya would quote them shillings
        // and offer them M-Pesa. "Somewhere else" at least tells the truth.
        assertThat(Country.of("PE")).isEqualTo(Country.OTHER);
        assertThat(Country.of("zz")).isEqualTo(Country.OTHER);
        // Blank means an install that predates the setting, which really is Kenya.
        assertThat(Country.of(null)).isEqualTo(Country.KE);
        assertThat(Country.of("")).isEqualTo(Country.KE);
        assertThat(Country.of("gh")).isEqualTo(Country.GH);
    }

    @Test
    @DisplayName("The picker carries everything the admin screen needs to explain a choice")
    void describeAllIsComplete() {
        for (Map<String, Object> row : Country.describeAll()) {
            assertThat(row).containsKeys("code", "name", "currency", "language",
                    "paymentBrand", "rail", "networks", "needsManualCollection");
        }
        assertThat(Country.describeAll()).hasSize(Country.values().length);
    }
}
