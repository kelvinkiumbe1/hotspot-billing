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
            List.of("M-Pesa", "Airtel Money")),
    TZ("Tanzania", "TZS", "sw", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("M-Pesa", "Mixx by Yas", "Airtel Money", "Halopesa")),
    UG("Uganda", "UGX", "en", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("MTN MoMo", "Airtel Money")),
    RW("Rwanda", "RWF", "en", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("MTN MoMo", "Airtel Money")),
    GH("Ghana", "GHS", "en", "MTN MoMo", Rail.PAYSTACK,
            List.of("MTN MoMo", "Telecel Cash", "AirtelTigo Money")),
    // Nigeria and South Africa are the exceptions: cards and bank transfer are
    // what people actually use, and mobile money barely registers.
    NG("Nigeria", "NGN", "en", "card or bank transfer", Rail.PAYSTACK,
            List.of("Bank transfer", "Card", "USSD")),
    ZA("South Africa", "ZAR", "en", "card or EFT", Rail.PAYSTACK,
            List.of("Card", "Instant EFT")),
    ZM("Zambia", "ZMW", "en", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("MTN MoMo", "Airtel Money", "Zamtel Kwacha")),
    MW("Malawi", "MWK", "en", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("Airtel Money", "TNM Mpamba")),
    MZ("Mozambique", "MZN", "pt", "M-Pesa", Rail.FLUTTERWAVE,
            List.of("M-Pesa", "e-Mola", "mKesh")),
    AO("Angola", "AOA", "pt", "Multicaixa", Rail.NONE,
            List.of("Multicaixa Express")),
    SN("Senegal", "XOF", "fr", "Orange Money", Rail.FLUTTERWAVE,
            List.of("Orange Money", "Wave", "Free Money")),
    CI("Côte d'Ivoire", "XOF", "fr", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("Orange Money", "MTN MoMo", "Moov Money", "Wave")),
    CM("Cameroon", "XAF", "fr", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("MTN MoMo", "Orange Money")),
    CD("DR Congo", "CDF", "fr", "Mobile Money", Rail.FLUTTERWAVE,
            List.of("M-Pesa", "Orange Money", "Airtel Money")),
    ET("Ethiopia", "ETB", "en", "Telebirr", Rail.NONE,
            List.of("Telebirr", "CBE Birr")),
    ZW("Zimbabwe", "USD", "en", "EcoCash", Rail.NONE,
            List.of("EcoCash")),
    OTHER("Somewhere else", "USD", "en", "card", Rail.STRIPE,
            List.of("Card"));

    /** Which of the built rails actually serves this country. */
    public enum Rail { MPESA, PAYSTACK, FLUTTERWAVE, STRIPE, NONE }

    private final String countryName;
    private final String currency;
    private final String language;
    private final String paymentBrand;
    private final Rail rail;
    private final List<String> networks;

    Country(String countryName, String currency, String language,
            String paymentBrand, Rail rail, List<String> networks) {
        this.countryName = countryName;
        this.currency = currency;
        this.language = language;
        this.paymentBrand = paymentBrand;
        this.rail = rail;
        this.networks = networks;
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
     * <p>Said out loud rather than left to be discovered: Telebirr, EcoCash and
     * Multicaixa are domestic systems that neither Paystack, Flutterwave nor
     * Stripe reaches, and an operator there needs to know that before they
     * launch rather than after their first customer cannot pay.
     */
    public boolean needsManualCollection() {
        return rail == Rail.NONE;
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
