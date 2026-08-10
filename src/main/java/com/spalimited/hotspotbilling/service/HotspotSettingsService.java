package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.HotspotSettings;
import com.spalimited.hotspotbilling.repository.HotspotSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class HotspotSettingsService {

    /** The captive-portal layouts the portal knows how to render. */
    private static final Set<String> TEMPLATES = Set.of("CLASSIC", "GRID", "MINIMAL");

    /** The captive-portal languages the portal ships translations for. */
    private static final Set<String> LANGUAGES = Set.of("EN", "SW");

    /** The visual themes the portal can render (keys mirror portalThemes.js). */
    private static final Set<String> THEMES_SET = Set.of(
            "AMBER", "EMERALD", "COBALT", "CRIMSON", "VIOLET",
            "NEON", "STEEL", "SLATE", "OCEAN", "ROSE");

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
        String template = in.getPortalTemplate() == null ? "" : in.getPortalTemplate().trim().toUpperCase();
        s.setPortalTemplate(TEMPLATES.contains(template) ? template : "CLASSIC");
        String lang = in.getDefaultLanguage() == null ? "" : in.getDefaultLanguage().trim().toUpperCase();
        s.setDefaultLanguage(LANGUAGES.contains(lang) ? lang : "EN");
        String theme = in.getPortalTheme() == null ? "" : in.getPortalTheme().trim().toUpperCase();
        s.setPortalTheme(THEMES_SET.contains(theme) ? theme : "AMBER");
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

    @Transactional(readOnly = true)
    public String portalTheme() {
        return get().getPortalTheme();
    }
}
