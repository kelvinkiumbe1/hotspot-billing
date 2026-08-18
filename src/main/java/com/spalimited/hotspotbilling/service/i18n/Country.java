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
    TZ("Tanzania", "TZS", "sw", "Mobile Money", Rail.FLUTTERWAVE,
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
    MW("Malawi", "MWK", "en", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("Airtel Money", "TNM Mpamba"), "265", 9, List.of()),
    MZ("Mozambique", "MZN", "pt", "M-Pesa", Rail.FLUTTERWAVE,
            List.of("M-Pesa", "e-Mola", "mKesh"), "258", 9, List.of()),
    AO("Angola", "AOA", "pt", "Multicaixa", Rail.NONE,
            List.of("Multicaixa Express"), "244", 9, List.of()),
    SN("Senegal", "XOF", "fr", "Orange Money", Rail.FLUTTERWAVE,
            List.of("Orange Money", "Wave", "Free Money"), "221", 9, List.of()),
    CI("Côte d'Ivoire", "XOF", "fr", "Mobile Money", Rail.MTN_MOMO,
            List.of("Orange Money", "MTN MoMo", "Moov Money", "Wave"), "225", 10, List.of()),
    CM("Cameroon", "XAF", "fr", "Mobile Money", Rail.MTN_MOMO,
            List.of("MTN MoMo", "Orange Money"), "237", 9, List.of()),
    CD("DR Congo", "CDF", "fr", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("M-Pesa", "Orange Money", "Airtel Money"), "243", 9, List.of()),
    ET("Ethiopia", "ETB", "en", "Telebirr", Rail.CHAPA,
            List.of("Telebirr", "CBE Birr", "M-Pesa", "Amole"), "251", 9, List.of()),
    ZW("Zimbabwe", "USD", "en", "EcoCash", Rail.PAYNOW,
            List.of("EcoCash", "OneMoney", "InnBucks", "Zimswitch"), "263", 9, List.of()),
    OTHER("Somewhere else", "USD", "en", "card", Rail.STRIPE,
            List.of("Card"), "", 0, List.of());

    /** Which of the built rails actually serves this country. */
    public enum Rail { MPESA, MTN_MOMO, PAYSTACK, FLUTTERWAVE, STRIPE, CHAPA, PAYNOW, NONE }

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
