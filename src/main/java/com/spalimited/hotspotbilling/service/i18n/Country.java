package com.spalimited.hotspotbilling.service.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the operator is, and what that implies.
 *
 * <p>Currency and language were made settings before this existed, which left
 * the country itself unknown — and currency is a poor stand-in for it. XOF is
 * eight countries, USD is many, and the thing that actually differs between
 * them is how customers pay.
 *
 * <p>The important field here is {@link #paymentBrand()}. Outside Nigeria and
 * South Africa, customers do not pay by card — they pay by mobile money, and
 * they call it by its brand. A Ghanaian shown "pay with M-Pesa" has been asked
 * for something that does not exist in Ghana; the same screen saying "pay with
 * MTN MoMo" is unremarkable. That one word is the difference between a portal
 * that works abroad and one that only looks like it does.
 */
public enum Country {

    KE("Kenya", "KES", "en", "M-Pesa", Rail.MPESA,
            List.of("M-Pesa", "Airtel Money"), "254", 9, List.of("7", "1")),
    // Vodacom M-Pesa rather than Flutterwave, now that it is reachable
    // directly. M-Pesa is the largest wallet in Tanzania by some way, and
    // Flutterwave was only ever the way to reach it -- an aggregator margin on
    // top of the wallet's own fee. Flutterwave stays switched on beside it for
    // the three wallets Vodacom does not reach.
    TZ("Tanzania", "TZS", "sw", "M-Pesa", Rail.VODACOM_MPESA,
            List.of("M-Pesa", "Mixx by Yas", "Airtel Money", "Halopesa"), "255", 9, List.of()),
    UG("Uganda", "UGX", "en", "Mobile Money", Rail.MTN_MOMO,
            List.of("MTN MoMo", "Airtel Money"), "256", 9, List.of()),
    RW("Rwanda", "RWF", "en", "Mobile Money", Rail.MTN_MOMO,
            List.of("MTN MoMo", "Airtel Money"), "250", 9, List.of()),
    GH("Ghana", "GHS", "en", "MTN MoMo", Rail.MTN_MOMO,
            List.of("MTN MoMo", "Telecel Cash", "AirtelTigo Money"), "233", 9, List.of()),
    // Nigeria and South Africa are the exceptions: cards and bank transfer are
    // what people actually use, and mobile money barely registers.
    NG("Nigeria", "NGN", "en", "card or bank transfer", Rail.PAYSTACK,
            List.of("Bank transfer", "Card", "USSD"), "234", 10, List.of()),
    ZA("South Africa", "ZAR", "en", "card or EFT", Rail.PAYSTACK,
            List.of("Card", "Instant EFT"), "27", 9, List.of()),
    ZM("Zambia", "ZMW", "en", "Mobile Money", Rail.MTN_MOMO,
            List.of("MTN MoMo", "Airtel Money", "Zamtel Kwacha"), "260", 9, List.of()),
    MW("Malawi", "MWK", "en", "Airtel Money", Rail.AIRTEL_MONEY,
            List.of("Airtel Money", "TNM Mpamba"), "265", 9, List.of()),
    MZ("Mozambique", "MZN", "pt", "M-Pesa", Rail.VODACOM_MPESA,
            List.of("M-Pesa", "e-Mola", "mKesh"), "258", 9, List.of()),
    AO("Angola", "AOA", "pt", "Multicaixa", Rail.NONE,
            List.of("Multicaixa Express"), "244", 9, List.of()),
    // Wave rather than Orange Money as the default, on price: Wave arrived at a
    // flat 1% against several times that and took a very large share of
    // Senegalese mobile money. An operator here wants both switched on, which is
    // now possible, so this only decides which one is listed first.
    SN("Senegal", "XOF", "fr", "Mobile Money", Rail.WAVE,
            List.of("Wave", "Orange Money", "Free Money"), "221", 9, List.of()),
    // Was MTN, which was never the biggest here — Orange Money is. MTN was the
    // default only because it was the rail that existed, and now that several
    // gateways can run at once an operator can offer Orange, MTN and Wave
    // together instead of the one this code happened to support.
    CI("Côte d'Ivoire", "XOF", "fr", "Mobile Money", Rail.ORANGE_MONEY,
            List.of("Orange Money", "MTN MoMo", "Wave", "Moov Money"), "225", 10, List.of()),
    CM("Cameroon", "XAF", "fr", "Mobile Money", Rail.MTN_MOMO,
            List.of("MTN MoMo", "Orange Money"), "237", 9, List.of()),
    CD("DR Congo", "CDF", "fr", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("M-Pesa", "Orange Money", "Airtel Money"), "243", 9, List.of()),
    ET("Ethiopia", "ETB", "en", "Telebirr", Rail.CHAPA,
            List.of("Telebirr", "CBE Birr", "M-Pesa", "Amole"), "251", 9, List.of()),
    ZW("Zimbabwe", "USD", "en", "EcoCash", Rail.PAYNOW,
            List.of("EcoCash", "OneMoney", "InnBucks", "Zimswitch"), "263", 9, List.of()),

    // --- Countries the rails already built reach, added without new integrations ---
    //
    // Each of these needed a table entry and a line in one provider's market
    // set, nothing more. The rails were already written and tested; these
    // markets were unreachable only because this file did not name them.

    // Orange Money country, and Wave competing on price the way it does in Senegal.
    ML("Mali", "XOF", "fr", "Orange Money", Rail.ORANGE_MONEY,
            List.of("Orange Money", "Moov Money", "Wave"), "223", 8, List.of()),
    BF("Burkina Faso", "XOF", "fr", "Mobile Money", Rail.ORANGE_MONEY,
            List.of("Orange Money", "Moov Money", "Wave"), "226", 8, List.of()),
    // Airtel is the largest network here; Orange Money also operates.
    NE("Niger", "XOF", "fr", "Mobile Money", Rail.AIRTEL_MONEY,
            List.of("Airtel Money", "Orange Money", "Moov Money"), "227", 8, List.of()),
    // GNF has no minor unit, like the CFA francs.
    GN("Guinea", "GNF", "fr", "Orange Money", Rail.ORANGE_MONEY,
            List.of("Orange Money", "MTN MoMo"), "224", 9, List.of()),
    // SLE, the leone as redenominated in 2022 — not the old SLL.
    SL("Sierra Leone", "SLE", "en", "Orange Money", Rail.ORANGE_MONEY,
            List.of("Orange Money", "Afrimoney"), "232", 8, List.of()),
    BW("Botswana", "BWP", "en", "Mobile Money", Rail.ORANGE_MONEY,
            List.of("Orange Money", "MyZaka", "Smega"), "267", 8, List.of()),

    TD("Chad", "XAF", "fr", "Airtel Money", Rail.AIRTEL_MONEY,
            List.of("Airtel Money", "Moov Money"), "235", 8, List.of()),
    GA("Gabon", "XAF", "fr", "Airtel Money", Rail.AIRTEL_MONEY,
            List.of("Airtel Money", "Moov Money"), "241", 8, List.of()),
    CG("Congo-Brazzaville", "XAF", "fr", "Mobile Money", Rail.AIRTEL_MONEY,
            List.of("Airtel Money", "MTN MoMo"), "242", 9, List.of()),
    // The honest caveat here: MVola is the biggest wallet in Madagascar and no
    // built rail reaches it. Airtel and Orange are reachable, so an operator can
    // sell — to some of the market, not most of it. Said plainly in the brand,
    // which stays generic rather than naming a wallet that cannot be charged.
    MG("Madagascar", "MGA", "fr", "Mobile Money", Rail.AIRTEL_MONEY,
            List.of("MVola", "Orange Money", "Airtel Money"), "261", 9, List.of()),

    GM("Gambia", "GMD", "en", "Wave", Rail.WAVE,
            List.of("Wave", "Africell Money", "QMoney"), "220", 7, List.of()),

    // MTN markets. These need the target environment MTN issues the merchant,
    // pasted under Settings — see MtnMomoProvider. Guessing the string per
    // country was the alternative, and a wrong one is refused by MTN with an
    // error that says nothing about why.
    BJ("Benin", "XOF", "fr", "MTN MoMo", Rail.MTN_MOMO,
            List.of("MTN MoMo", "Moov Money"), "229", 10, List.of()),
    SZ("Eswatini", "SZL", "en", "MTN MoMo", Rail.MTN_MOMO,
            List.of("MTN MoMo"), "268", 8, List.of()),
    SS("South Sudan", "SSP", "en", "Mobile Money", Rail.MTN_MOMO,
            List.of("MTN MoMo", "m-Gurush"), "211", 9, List.of()),

    // The fourth Vodacom M-Pesa market, and the one the rail was built without.
    // vodacomLES is live -- confirmed by opening a session against it -- so this
    // is finishing an integration rather than starting one. The loti is pegged
    // to the rand one-for-one and South African cards are widely taken, but
    // M-Pesa is what a hotspot customer actually has.
    LS("Lesotho", "LSL", "en", "M-Pesa", Rail.VODACOM_MPESA,
            List.of("M-Pesa", "EcoCash"), "266", 8, List.of()),

    // Airtel Africa's fourteenth market, and the one our Airtel rail was
    // missing. Small -- a hundred thousand people -- but it costs a line, and a
    // country reachable by a built rail should not read as unreachable.
    SC("Seychelles", "SCR", "en", "Airtel Money", Rail.AIRTEL_MONEY,
            List.of("Airtel Money", "Card"), "248", 7, List.of()),

    // The largest market on the continent and the last big one unreached.
    // Nothing here is mobile money in the East African sense: Egyptians pay by
    // wallet, by InstaPay and by card, and Paymob is the one integration that
    // covers all three.
    //
    // Listed as English because that is the only language this system has for
    // it. Arabic is a real gap and a bigger job than a country entry -- every
    // string in Messages, and right-to-left in the portal.
    EG("Egypt", "EGP", "en", "Vodafone Cash or card", Rail.PAYMOB,
            List.of("Vodafone Cash", "InstaPay", "Meeza", "Card"), "20", 10, List.of()),

    // Tunisia. The dinar is one of the few currencies with three decimal
    // places rather than two -- a thousand millimes -- which is the single thing
    // to be careful about here. Set Currency decimals to 3 under Branding, or
    // prices will be shown rounded to the piastre-equivalent and read wrong.
    //
    // French because that is the language this system has for it; Arabic is the
    // other half of the country and a bigger job than a country entry.
    TN("Tunisia", "TND", "fr", "wallet or card", Rail.KONNECT,
            List.of("Konnect", "Flouci", "e-DINAR", "Card"), "216", 8, List.of()),

    // Somalia. Mobile money is more universal here than in Kenya and the
    // country is full of small independent ISPs, none of which any aggregator
    // reaches -- which makes this worth far more than the population suggests.
    //
    // The currency is USD and that is not a shortcut: Somalia is dollarised in
    // practice, EVC Plus prices in dollars, and the shilling barely circulates.
    SO("Somalia", "USD", "en", "EVC Plus", Rail.WAAFIPAY,
            List.of("EVC Plus", "Zaad", "Sahal"), "252", 9, List.of()),

    OTHER("Somewhere else", "USD", "en", "card", Rail.STRIPE,
            List.of("Card"), "", 0, List.of());

    /**
     * Which of the built rails actually serves this country.
     *
     * <p>A direct rail is cheaper than an aggregator, and some of them prompt the
     * handset instead of opening a page. But it reaches only that provider's own
     * customers, so it is the right <em>default</em> where one dominates — MTN in
     * Ghana, Airtel in Malawi, Safaricom in Kenya, Wave in Senegal on price.
     *
     * <p>This is now only which rail is offered first. Several can be switched on
     * at once, so a Tanzanian or Ivorian operator can run three side by side. The
     * aggregator defaults that remain — Tanzania, Mozambique, the DRC — are markets
     * where no single wallet has a majority and one integration genuinely reaches
     * more customers than any one telco would.
     */
    public enum Rail {
        MPESA, VODACOM_MPESA, MTN_MOMO, AIRTEL_MONEY, ORANGE_MONEY, WAVE,
        PAYSTACK, FLUTTERWAVE, STRIPE, CHAPA, PAYNOW, PAYMOB, KONNECT, WAAFIPAY, NONE
    }

    private final String countryName;
    private final String currency;
    private final String language;
    private final String paymentBrand;
    private final Rail rail;
    private final List<String> networks;
    private final String diallingCode;
    private final int nationalLength;
    private final List<String> mobilePrefixes;

    Country(String countryName, String currency, String language,
            String paymentBrand, Rail rail, List<String> networks,
            String diallingCode, int nationalLength, List<String> mobilePrefixes) {
        this.countryName = countryName;
        this.currency = currency;
        this.language = language;
        this.paymentBrand = paymentBrand;
        this.rail = rail;
        this.networks = networks;
        this.diallingCode = diallingCode;
        this.nationalLength = nationalLength;
        this.mobilePrefixes = mobilePrefixes;
    }

    public String countryName() {
        return countryName;
    }

    public String currency() {
        return currency;
    }

    public String language() {
        return language;
    }

    /**
     * What to call paying, on a customer's screen, in this country.
     *
     * <p>A brand where one dominates ("M-Pesa", "MTN MoMo"), the generic term
     * where several compete and naming one would exclude the rest, and the
     * plain instrument where mobile money is not the norm.
     */
    public String paymentBrand() {
        return paymentBrand;
    }

    public Rail rail() {
        return rail;
    }

    /** Everything customers here might pay with, for the operator's own reference. */
    public List<String> networks() {
        return networks;
    }

    /**
     * True where no built rail reaches — the operator has to reconcile by hand.
     *
     * <p>Only Angola now. Multicaixa Express is domestic and none of the built
     * gateways touch it, so an operator there needs to know before they launch
     * rather than after their first customer cannot pay.
     *
     * <p>Ethiopia and Zimbabwe were listed here and should not have been:
     * Chapa reaches telebirr and Zimbabwe's Paynow reaches EcoCash. Calling a
     * country unreachable when it is not turns a working market away.
     */
    public boolean needsManualCollection() {
        return rail == Rail.NONE;
    }

    /** The international dialling code, without the plus. Empty for OTHER. */
    public String diallingCode() {
        return diallingCode;
    }

    /**
     * How many digits a number here has once the trunk zero is stripped.
     *
     * <p>Length is checked and the mobile prefix deliberately is not. Networks
     * are issued new prefixes regularly, and a table that lists them goes out
     * of date silently — as a customer being told their real number is invalid.
     * Rejecting a paying customer is far worse than accepting a number the
     * gateway will bounce a moment later.
     */
    public int nationalLength() {
        return nationalLength;
    }

    /**
     * Whether a national number starts the way one from here does.
     *
     * <p>Empty for most countries on purpose: networks are issued new ranges
     * regularly and a stale list rejects real customers. It is populated only
     * where the prefixes are long-settled AND the old Kenya-only code already
     * enforced them — dropping that check would quietly make this system
     * accept numbers it used to refuse.
     */
    boolean prefixLooksRight(String national) {
        if (mobilePrefixes.isEmpty()) {
            return true;
        }
        return mobilePrefixes.stream().anyMatch(national::startsWith);
    }

    /** The country whose dialling code these digits start with, if any. */
    static Country byDiallingPrefix(String digits) {
        Country best = null;
        for (Country country : values()) {
            if (country.diallingCode.isEmpty() || !digits.startsWith(country.diallingCode)) {
                continue;
            }
            // Longest code wins: "254" must beat "25" if such a country existed,
            // and the same logic keeps "27" from swallowing "270"-style codes.
            if (best == null || country.diallingCode.length() > best.diallingCode.length()) {
                best = country;
            }
        }
        return best;
    }

    public static Country of(String code) {
        if (code == null || code.isBlank()) {
            return KE;
        }
        String wanted = code.trim().toUpperCase(Locale.ROOT);
        for (Country country : values()) {
            if (country.name().equals(wanted)) {
                return country;
            }
        }
        return OTHER;
    }

    /** For the admin picker, with everything it needs to explain each choice. */
    public static List<Map<String, Object>> describeAll() {
        return java.util.Arrays.stream(values()).map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.name());
            row.put("name", c.countryName);
            row.put("currency", c.currency);
            row.put("language", c.language);
            row.put("paymentBrand", c.paymentBrand);
            row.put("rail", c.rail.name());
            row.put("networks", c.networks);
            row.put("needsManualCollection", c.needsManualCollection());
            return row;
        }).toList();
    }
}
