package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.HotspotSettings;
import com.spalimited.hotspotbilling.repository.HotspotSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class HotspotSettingsService {

    /** The complete portal designs the portal renders (keys mirror portalDesigns.js). */
    private static final Set<String> DESIGNS = Set.of(
            "CLASSIC", "BREEZE", "POSTER", "MATRIX", "STEPS", "NEON");

    /** Values from before designs replaced layout templates + colour themes. */
    private static final Map<String, String> LEGACY_DESIGNS = Map.of(
            "GRID", "MATRIX",
            "MINIMAL", "BREEZE");

    /** The captive-portal languages the portal ships translations for. */
    private static final Set<String> LANGUAGES = Set.of("EN", "SW");

    private final HotspotSettingsRepository repo;

    @Transactional
    public HotspotSettings get() {
        return repo.findById(HotspotSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(HotspotSettings.builder()
                        .id(HotspotSettings.SINGLETON_ID)
                        .unusedVoucherExpiryDays(0)
                        .build()));
    }

    @Transactional
    public HotspotSettings update(HotspotSettings in) {
        HotspotSettings s = get();
        String redirect = in.getPostPurchaseRedirect();
        s.setPostPurchaseRedirect(redirect == null || redirect.isBlank() ? null : redirect.trim());
        s.setUnusedVoucherExpiryDays(Math.max(0, Math.min(3650, in.getUnusedVoucherExpiryDays())));
        String design = in.getPortalTemplate() == null ? "" : in.getPortalTemplate().trim().toUpperCase();
        design = LEGACY_DESIGNS.getOrDefault(design, design);
        s.setPortalTemplate(DESIGNS.contains(design) ? design : "CLASSIC");
        String lang = in.getDefaultLanguage() == null ? "" : in.getDefaultLanguage().trim().toUpperCase();
        s.setDefaultLanguage(LANGUAGES.contains(lang) ? lang : "EN");
        return repo.save(s);
    }

    @Transactional(readOnly = true)
    public String postPurchaseRedirect() {
        return get().getPostPurchaseRedirect();
    }

    @Transactional(readOnly = true)
    public int unusedVoucherExpiryDays() {
        return get().getUnusedVoucherExpiryDays();
    }

    @Transactional(readOnly = true)
    public String portalTemplate() {
        return get().getPortalTemplate();
    }

    @Transactional(readOnly = true)
    public String defaultLanguage() {
        return get().getDefaultLanguage();
    }
}
