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
 * Hotspot lifecycle settings, one row (id = 1). Kept separate from the
 * branding-focused PortalSettings so each has its own Settings page and a
 * save on one never overwrites the other.
 */
@Entity
@Table(name = "hotspot_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HotspotSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Which portal design the customer sees (keys mirror portalDesigns.js), e.g. CLASSIC. */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String portalTemplate = "CLASSIC";

    /** Default captive-portal language: EN (English) or SW (Swahili). */
    @Builder.Default
    @Column(nullable = false, length = 5)
    private String defaultLanguage = "EN";

    /** Where to send a customer after a successful purchase (blank = stay). */
    @Column(length = 512)
    private String postPurchaseRedirect;

    /** Auto-invalidate an unused voucher this many days after it was made. 0 = never. */
    @Builder.Default
    @Column(nullable = false)
    private int unusedVoucherExpiryDays = 0;
}
