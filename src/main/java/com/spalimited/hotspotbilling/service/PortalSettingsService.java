package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.repository.PortalSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Branding and copy for the captive portal. */
@Service
@RequiredArgsConstructor
public class PortalSettingsService {

    private final PortalSettingsRepository repository;

    @Transactional
    public PortalSettings settings() {
        return repository.findById(PortalSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(PortalSettings.builder()
                        .id(PortalSettings.SINGLETON_ID)
                        // Identity starts blank — a new ISP fills in their own
                        // name, copy, support line and terms (the onboarding
                        // checklist nudges them). The portal falls back to
                        // generic copy until then, so it still renders.
                        .businessName("")
                        .headline("")
                        .subheadline("")
                        .supportPhone("")
                        .termsText("")
                        // Colours keep sensible brand defaults so the portal
                        // looks intentional out of the box; the ISP can change them.
                        .backgroundColor("#000000")
                        .accentColor("#FDBF2D")
                        .trialEnabled(false)
                        .trialMinutes(15)
                        .build()));
    }

    @Transactional
    public PortalSettings update(PortalSettings updated) {
        PortalSettings current = settings();
        current.setBusinessName(updated.getBusinessName());
        current.setHeadline(updated.getHeadline());
        current.setSubheadline(updated.getSubheadline());
        current.setBackgroundColor(updated.getBackgroundColor());
        current.setAccentColor(updated.getAccentColor());
        current.setSupportPhone(updated.getSupportPhone());
        current.setTermsText(updated.getTermsText());
        current.setTrialEnabled(updated.isTrialEnabled());
        current.setTrialMinutes(updated.getTrialMinutes() > 0 ? updated.getTrialMinutes() : 15);
        if (updated.getLogoFilename() != null) {
            current.setLogoFilename(updated.getLogoFilename());
        }
        return repository.save(current);
    }

    @Transactional
    public PortalSettings setLogo(String filename) {
        PortalSettings current = settings();
        current.setLogoFilename(filename);
        return repository.save(current);
    }
}
