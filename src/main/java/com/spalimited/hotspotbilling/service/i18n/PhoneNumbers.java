package com.spalimited.hotspotbilling.service.i18n;

import com.spalimited.hotspotbilling.service.PortalSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Turning whatever a customer typed into one canonical number.
 *
 * <p>Five near-identical copies of a Kenyan normaliser used to live across the
 * services, each hardcoding "254". That is why a Ghanaian ISP could set their
 * country, currency and language correctly and still not sell a single pass:
 * every 233 number was rejected before it reached a gateway.
 *
 * <p>The output shape is unchanged — digits only, dialling code first, no plus
 * — so every number already stored stays valid and nothing has to be migrated.
 * For a Kenyan operator this produces exactly what the old code produced, which
 * is the property the tests pin down first.
 *
 * <h2>What is checked, and what deliberately is not</h2>
 * Length and dialling code are always checked. The mobile prefix is checked
 * only where a country declares one, which today is Kenya alone — because the
 * code this replaced enforced it there, and quietly dropping a check is a
 * change nobody asked for. Everywhere else the prefix is left alone on
 * purpose: networks are issued new ranges regularly, and a hardcoded list goes
 * out of date silently, showing up as a customer being told their real number
 * is invalid. Accepting a number the gateway will bounce a second later costs
 * nothing; turning away somebody trying to pay costs the sale and the customer.
 */
@Service
@RequiredArgsConstructor
public class PhoneNumbers {

    /** E.164 allows fifteen digits in total, including the dialling code. */
    private static final int E164_MAX = 15;

    private final PortalSettingsService portalSettings;

    /** Where this operator is, or Kenya if the setting cannot be read. */
    public Country country() {
        try {
            return Country.of(portalSettings.settings().getCountry());
        } catch (Exception e) {
            return Country.KE;
        }
    }

    /**
     * The canonical form of a number, or null when it cannot be one.
     *
     * <p>Accepts the shapes people actually type: {@code 0712 345 678},
     * {@code +254 712 345 678}, {@code 00254712345678}, {@code 712345678} and
     * the already-canonical {@code 254712345678}.
     */
    public String normalise(String raw) {
        return normalise(raw, country());
    }

    /** The same, against a stated country — used by tests and by imports. */
    public static String normalise(String raw, Country home) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        // "00" is the international access code in most of the world. Dropping
        // it turns 00254712345678 into a plain international number.
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.length() > E164_MAX) {
            return null;
        }

        // Already international, for anywhere we know. Checked before the local
        // rules so an operator near a border can serve the other side of it —
        // a Kenyan ISP with Ugandan customers is ordinary, and the old code
        // rejected every one of them.
        Country foreign = Country.byDiallingPrefix(digits);
        if (foreign != null) {
            int rest = digits.length() - foreign.diallingCode().length();
            if (rest == foreign.nationalLength()) {
                return digits;
            }
        }

        String code = home.diallingCode();
        int wanted = home.nationalLength();
        if (code.isEmpty() || wanted == 0) {
            // "Somewhere else": no rules to apply, so anything of a credible
            // international length passes through untouched rather than being
            // mangled into a country it does not belong to.
            return digits.length() >= 8 ? digits : null;
        }

        // A trunk zero, as it is written on a poster or a shopfront.
        if (digits.startsWith("0") && digits.length() - 1 == wanted) {
            return accept(home, code, digits.substring(1));
        }
        // The bare national number, as people say it out loud.
        if (digits.length() == wanted) {
            return accept(home, code, digits);
        }
        // Already ours, in full.
        if (digits.startsWith(code) && digits.length() - code.length() == wanted) {
            return accept(home, code, digits.substring(code.length()));
        }
        return null;
    }

    /**
     * The full number, if the national part starts the way one from here does.
     *
     * <p>Only Kenya declares prefixes, and only because the code this replaced
     * enforced them. Without this the change would have quietly loosened
     * Kenya: 0241234567 is a Ghanaian number, and length alone would turn it
     * into a Kenyan one belonging to nobody.
     */
    private static String accept(Country home, String code, String national) {
        return home.prefixLooksRight(national) ? code + national : null;
    }

    /** Whether this could be dialled — the question the validator asks. */
    public boolean isValid(String raw) {
        return normalise(raw) != null;
    }

    /**
     * A normalised number, or the digits as typed when it cannot be read.
     *
     * <p>For places that must record something rather than drop it — an
     * unmatched paybill payment is still evidence, even from a number we
     * cannot parse.
     */
    public String loose(String raw) {
        String clean = normalise(raw);
        return clean != null ? clean : (raw == null ? "" : raw.replaceAll("\\D", ""));
    }

    /**
     * What to show a customer as the shape to type, e.g. "2547XXXXXXXX".
     *
     * <p>Built from the country rather than written into a message, because the
     * example is the message: telling a Ghanaian to type 2547XXXXXXXX is worse
     * than saying nothing.
     */
    public String example() {
        Country home = country();
        if (home.diallingCode().isEmpty() || home.nationalLength() == 0) {
            return "your number, with its country code";
        }
        return home.diallingCode() + "X".repeat(home.nationalLength());
    }
}
