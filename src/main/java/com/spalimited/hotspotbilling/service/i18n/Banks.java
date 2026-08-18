package com.spalimited.hotspotbilling.service.i18n;

import java.util.List;
import java.util.Map;

/**
 * The banks an operator is likely to hold an account with, per country.
 *
 * <p>Exists because the bank name on a manual gateway was free text shown
 * straight to customers. "Equty Bank" is not a bank, and nothing anywhere
 * would have said so — the operator finds out when a customer rings to ask
 * where to send the money.
 *
 * <p>A picklist cannot make an account <em>number</em> right, and nothing here
 * pretends otherwise. It removes one class of mistake completely; the number
 * is guarded separately, by making the operator type it twice.
 *
 * <p>Every list ends with an escape hatch. A country's banking sector is not
 * a fixed set, and a picklist an operator cannot get out of is worse than the
 * free text it replaced.
 */
public final class Banks {

    private Banks() {
    }

    /** Shown last, always, so no operator is ever stuck. */
    public static final String OTHER = "Other — type it in";

    private static final Map<Country, List<String>> BY_COUNTRY = Map.ofEntries(
            Map.entry(Country.KE, List.of(
                    "Equity Bank", "KCB Bank", "Co-operative Bank", "NCBA Bank", "Absa Bank Kenya",
                    "Standard Chartered", "Stanbic Bank", "Diamond Trust Bank", "I&M Bank",
                    "Family Bank", "Sidian Bank", "National Bank of Kenya", "Prime Bank",
                    "HFC Bank", "Gulf African Bank", "SBM Bank", "Ecobank Kenya", "UBA Kenya",
                    "Bank of Africa", "Credit Bank", "Kingdom Bank", "Faulu MFB", "KWFT")),
            Map.entry(Country.TZ, List.of(
                    "CRDB Bank", "NMB Bank", "NBC Bank", "Stanbic Bank", "Absa Bank Tanzania",
                    "Exim Bank", "DTB Tanzania", "Equity Bank Tanzania", "Standard Chartered",
                    "Azania Bank", "TCB Bank", "Ecobank Tanzania")),
            Map.entry(Country.UG, List.of(
                    "Stanbic Bank Uganda", "Centenary Bank", "Absa Bank Uganda", "dfcu Bank",
                    "Equity Bank Uganda", "Standard Chartered", "DTB Uganda", "Bank of Africa",
                    "Housing Finance Bank", "PostBank Uganda", "KCB Uganda", "Ecobank Uganda")),
            Map.entry(Country.RW, List.of(
                    "Bank of Kigali", "Equity Bank Rwanda", "I&M Bank Rwanda", "Access Bank Rwanda",
                    "BPR Bank Rwanda", "Ecobank Rwanda", "GT Bank Rwanda", "NCBA Rwanda")),
            Map.entry(Country.GH, List.of(
                    "GCB Bank", "Ecobank Ghana", "Absa Bank Ghana", "Stanbic Bank Ghana",
                    "Fidelity Bank Ghana", "Standard Chartered", "Zenith Bank Ghana",
                    "CalBank", "Access Bank Ghana", "GT Bank Ghana", "ADB Bank",
                    "Republic Bank Ghana", "UBA Ghana", "Consolidated Bank Ghana")),
            Map.entry(Country.NG, List.of(
                    "Access Bank", "Zenith Bank", "First Bank of Nigeria", "GTBank", "UBA",
                    "Fidelity Bank", "Union Bank", "Sterling Bank", "Stanbic IBTC", "FCMB",
                    "Wema Bank", "Polaris Bank", "Keystone Bank", "Ecobank Nigeria",
                    "Providus Bank", "Kuda", "Moniepoint MFB", "Opay")),
            Map.entry(Country.ZA, List.of(
                    "Standard Bank", "FNB", "Absa", "Nedbank", "Capitec", "Investec",
                    "African Bank", "TymeBank", "Discovery Bank", "Bidvest Bank")),
            Map.entry(Country.ZM, List.of(
                    "Zanaco", "Stanbic Bank Zambia", "Absa Bank Zambia", "FNB Zambia",
                    "Standard Chartered", "Indo Zambia Bank", "Access Bank Zambia", "Ecobank Zambia")),
            Map.entry(Country.MW, List.of(
                    "National Bank of Malawi", "Standard Bank Malawi", "NBS Bank", "FDH Bank",
                    "First Capital Bank", "CDH Investment Bank", "Ecobank Malawi")),
            Map.entry(Country.MZ, List.of(
                    "Millennium BIM", "BCI", "Standard Bank Moçambique", "Absa Bank Moçambique",
                    "Moza Banco", "Banco Letshego", "FNB Moçambique", "Ecobank Moçambique")),
            Map.entry(Country.AO, List.of(
                    "Banco BAI", "Banco BFA", "Banco BIC", "Banco Atlântico", "Banco Sol",
                    "Standard Bank Angola", "Banco Millennium Atlântico", "Banco Keve")),
            Map.entry(Country.SN, List.of(
                    "CBAO", "SGBS", "Ecobank Sénégal", "BOA Sénégal", "BICIS", "UBA Sénégal",
                    "Banque Atlantique", "Coris Bank", "Orabank Sénégal")),
            Map.entry(Country.CI, List.of(
                    "SGCI", "Ecobank Côte d'Ivoire", "NSIA Banque", "BACI", "BOA Côte d'Ivoire",
                    "UBA Côte d'Ivoire", "Banque Atlantique", "Coris Bank", "BNI")),
            Map.entry(Country.CM, List.of(
                    "Afriland First Bank", "SGC", "BICEC", "Ecobank Cameroun", "UBA Cameroun",
                    "Commercial Bank of Cameroon", "Standard Chartered", "BGFIBank Cameroun")),
            Map.entry(Country.CD, List.of(
                    "Rawbank", "Equity BCDC", "TMB", "Ecobank RDC", "Access Bank RDC",
                    "FBN Bank RDC", "Sofibanque", "Afriland First Bank CD")),
            Map.entry(Country.ET, List.of(
                    "Commercial Bank of Ethiopia", "Awash Bank", "Dashen Bank", "Abyssinia Bank",
                    "Wegagen Bank", "Nib Bank", "Hibret Bank", "Zemen Bank", "Oromia Bank")),
            Map.entry(Country.ZW, List.of(
                    "CBZ Bank", "Stanbic Bank Zimbabwe", "Steward Bank", "FBC Bank",
                    "Nedbank Zimbabwe", "ZB Bank", "First Capital Bank", "NMB Bank Zimbabwe")));

    /** The banks for a country, always ending with the escape hatch. */
    public static List<String> forCountry(Country country) {
        List<String> known = BY_COUNTRY.get(country);
        if (known == null) {
            return List.of(OTHER);
        }
        return java.util.stream.Stream.concat(known.stream(), java.util.stream.Stream.of(OTHER))
                .toList();
    }
}
