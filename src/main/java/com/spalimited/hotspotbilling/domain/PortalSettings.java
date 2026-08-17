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
