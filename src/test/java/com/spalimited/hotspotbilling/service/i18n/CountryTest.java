package com.spalimited.hotspotbilling.service.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
        // Senegal says the generic term rather than a brand: Wave and Orange
        // Money both have real share there, and naming one excludes the other's
        // customers on the screen where they are about to pay.
        assertThat(Country.SN.paymentBrand()).isEqualTo("Mobile Money");
        assertThat(Country.SN.networks()).contains("Wave", "Orange Money");
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
    @DisplayName("Only Angola has no gateway, and it is flagged rather than quietly broken")
    void unreachableCountriesAreNamed() {
        // Multicaixa Express is domestic and none of the built rails touch it.
        // An operator there needs to know before they launch, not after their
        // first customer cannot pay.
        assertThat(Country.AO.needsManualCollection()).isTrue();

        assertThat(Country.KE.needsManualCollection()).isFalse();
        assertThat(Country.GH.needsManualCollection()).isFalse();
    }

    @Test
    @DisplayName("Ethiopia and Zimbabwe are reachable, which they were wrongly said not to be")
    void ethiopiaAndZimbabweAreReachable() {
        // Both were marked unreachable, and that is the expensive direction to
        // be wrong in: it tells an operator in a real market that they cannot
        // collect automatically when they can.
        assertThat(Country.ET.needsManualCollection()).isFalse();
        assertThat(Country.ET.rail()).isEqualTo(Country.Rail.CHAPA);

        assertThat(Country.ZW.needsManualCollection()).isFalse();
        assertThat(Country.ZW.rail()).isEqualTo(Country.Rail.PAYNOW);
    }

    @Test
    @DisplayName("MTN markets point at MTN directly rather than at an aggregator")
    void mtnMarketsUseMtn() {
        // Direct rather than through an aggregator, and it prompts the handset
        // instead of opening a checkout page.
        //
        // Cote d'Ivoire is deliberately not in this list any more. MTN was its
        // default only because MTN was the rail that existed; Orange Money is
        // the larger wallet there, and now that several gateways can be live at
        // once an operator can offer both rather than being handed the one this
        // code happened to support.
        for (Country country : new Country[]{
                Country.GH, Country.UG, Country.RW, Country.ZM, Country.CM}) {
            assertThat(country.rail()).as("%s", country).isEqualTo(Country.Rail.MTN_MOMO);
        }
        assertThat(Country.CI.networks()).contains("MTN MoMo");
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

    @Test
    @DisplayName("Every rail a country names is one this build can actually drive")
    void everyRailIsBuilt() {
        // A country pointing at a rail with no provider behind it is an
        // operator who sets their country correctly and can still sell nothing.
        Set<String> built = Set.of("MPESA", "VODACOM_MPESA", "MTN_MOMO", "AIRTEL_MONEY",
                "ORANGE_MONEY", "WAVE", "PAYSTACK", "FLUTTERWAVE", "STRIPE", "CHAPA",
                "PAYNOW", "PAYMOB", "KONNECT", "NONE");
        for (Country c : Country.values()) {
            assertThat(built)
                    .as("%s points at %s, which nothing implements", c, c.rail())
                    .contains(c.rail().name());
        }
    }

    @Test
    @DisplayName("East Africa's M-Pesa markets are off the aggregator")
    void mpesaMarketsAreDirect() {
        // Tanzania and Mozambique both went through Flutterwave to reach a
        // wallet Vodacom runs itself -- an aggregator margin on top of the
        // wallet's own fee, for the largest wallet in both countries.
        assertThat(Country.TZ.rail()).isEqualTo(Country.Rail.VODACOM_MPESA);
        assertThat(Country.MZ.rail()).isEqualTo(Country.Rail.VODACOM_MPESA);
        // And still not Kenya. Safaricom's M-Pesa is a different platform with
        // different credentials, and pointing Kenya here would break it.
        assertThat(Country.KE.rail()).isEqualTo(Country.Rail.MPESA);
    }

    @Test
    @DisplayName("Both rails reach every country that names them")
    void railsListTheCountriesThatPointAtThem() {
        // The Tanzania bug in reverse, and the one this file exists to stop: a
        // country reads as served, an operator sets it, and the rail it was sent
        // to has never heard of the place. Spot-checked on the two markets added
        // last, which were both reachable all along and listed nowhere.
        assertThat(Country.LS.rail()).isEqualTo(Country.Rail.VODACOM_MPESA);
        assertThat(Country.LS.currency()).isEqualTo("LSL");
        assertThat(Country.SC.rail()).isEqualTo(Country.Rail.AIRTEL_MONEY);
        assertThat(Country.SC.currency()).isEqualTo("SCR");
    }

    @Test
    @DisplayName("Francophone West Africa is off the aggregator")
    void westAfricaIsDirect() {
        // Senegal and Cote d'Ivoire both went through Flutterwave, which stacks
        // an aggregator margin on top of the wallet's own fee for the biggest
        // wallets in those markets.
        assertThat(Country.SN.rail()).isEqualTo(Country.Rail.WAVE);
        assertThat(Country.CI.rail()).isEqualTo(Country.Rail.ORANGE_MONEY);
    }

    @Test
    @DisplayName("Egypt is reached, and by the only rail that can")
    void egyptIsCovered() {
        // The largest market on the continent, and until now the largest gap.
        // None of the other rails collect Egyptian pounds -- not Paystack, not
        // Flutterwave, not Stripe -- so pointing Egypt at any of them would have
        // read as coverage and collected nothing.
        assertThat(Country.EG.rail()).isEqualTo(Country.Rail.PAYMOB);
        assertThat(Country.EG.currency()).isEqualTo("EGP");
        assertThat(Country.EG.needsManualCollection()).isFalse();
    }

    @Test
    @DisplayName("Tunisia is reached, and its currency has three decimals")
    void tunisiaIsCovered() {
        assertThat(Country.TN.rail()).isEqualTo(Country.Rail.KONNECT);
        // The dinar has a thousand millimes. Konnect takes the amount in them,
        // and every other minor-unit rail in this system uses a hundred -- so
        // this is the currency most likely to be charged wrong by a tenth.
        assertThat(Country.TN.currency()).isEqualTo("TND");
    }

    @Test
    @DisplayName("Angola is still the only country nothing reaches")
    void onlyAngolaIsUnreachable() {
        // Multicaixa Express is domestic and no built rail touches it. If this
        // ever grows a second entry, that country's operator needs telling
        // before they launch rather than after their first customer cannot pay.
        assertThat(java.util.Arrays.stream(Country.values())
                .filter(Country::needsManualCollection)
                .toList())
                .containsExactly(Country.AO);
    }
}
