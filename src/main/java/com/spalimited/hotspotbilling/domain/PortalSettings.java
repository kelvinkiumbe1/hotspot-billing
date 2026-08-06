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
