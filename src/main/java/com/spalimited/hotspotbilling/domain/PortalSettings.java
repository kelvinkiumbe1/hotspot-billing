package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Branding and copy for the customer captive portal, editable from the
 * admin so the business does not need a redeploy to change its name,
 * colours, terms or free-trial offer. Single row (id = 1).
 */
@Entity
@Table(name = "portal_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private String businessName;

    // --- Money ---

    /**
     * ISO 4217 code for everything this operator charges in. Kenyan Shillings
     * by default, because that is what every existing deployment uses and a
     * silent change of currency would be the worst possible upgrade.
     */
    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currencyCode = "KES";

    /**
     * What the customer sees. Held separately from the code because "KES 500"
     * is how Kenya writes it and "₦500" is how Nigeria does — the spacing and
     * the position differ, not only the letters. Blank falls back to the code.
     */
    @Column(length = 8)
    private String currencySymbol;

    /** True where the symbol trails the amount, as in "500 FCFA". */
    @Builder.Default
    @Column(nullable = false)
    private boolean currencySuffix = false;

    /** Shillings and naira are quoted whole; dollars and euros are not. */
    @Builder.Default
    @Column(nullable = false)
    private int currencyDecimals = 0;

    /**
     * The language customers are served in, as a two-letter code.
     *
     * <p>Separate from currency because the two do not travel together: an
     * operator in Abidjan quotes francs and speaks French, one in Kampala
     * quotes shillings and speaks English, and one in Nairobi may want
     * Swahili with the same shillings as their neighbour using English.
     */
    @Builder.Default
    @Column(nullable = false, length = 8)
    private String language = "en";

    /**
     * Whether a customer's own phone or browser overrides the setting above.
     *
     * <p>Changes nothing for a deployment where everyone reads the same
     * language. For a bilingual city it is the difference between one choice
     * that suits half the customers and each customer reading their own.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean followCustomerLanguage = true;

    /**
     * Where the operator is. Drives what paying is called and which rail suits
     * their customers; currency and language default from it but stay
     * independent, because an operator may bill in dollars from Kampala.
     */
    @Builder.Default
    @Column(nullable = false, length = 8)
    private String country = "KE";

    /**
     * What paying is called on a customer's screen.
     *
     * <p>Null means "use the country's default". Held separately so an operator
     * who knows their market can overrule the table — a Nairobi ISP whose
     * customers all use Airtel Money should not be forced to say "M-Pesa".
     */
    @Column(length = 40)
    private String paymentBrand;

    private String headline;

    private String subheadline;

    /** Filename under the upload dir, served at /api/uploads/{name}. */
    private String logoFilename;

    /** Hex colours driving the portal theme. */
    private String backgroundColor;

    private String accentColor;

    private String supportPhone;

    @Column(length = 4000)
    private String termsText;

    // --- Free trial ---

    @Builder.Default
    @Column(nullable = false)
    private boolean trialEnabled = false;

    @Builder.Default
    @Column(nullable = false)
    private int trialMinutes = 15;
}
