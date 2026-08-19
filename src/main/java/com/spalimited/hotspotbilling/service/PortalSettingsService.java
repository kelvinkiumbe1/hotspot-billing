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

    /**
     * Saves the arrangement, and only the arrangement.
     *
     * <p>Its own method rather than fields on {@link #update} for the reason that
     * method is full of "blank means leave it alone" comments: the branding form
     * does not know about layout, and folding these in would let saving a logo
     * reset every block to its default order. Separate endpoints cannot do that
     * to each other.
     */
    @Transactional
    public PortalSettings updateLayout(java.util.List<String> order,
                                       java.util.List<String> hidden,
                                       String align, Integer radius, String logoSize,
                                       String headingFont, String density) {
        PortalSettings current = settings();
        current.setSectionOrder(PortalLayout.clean(order));
        current.setSectionsHidden(PortalLayout.cleanHidden(hidden));
        // Null through, deliberately: null is "leave the design alone" and is a
        // real choice an operator makes by picking Default, not an absent value.
        current.setContentAlign(PortalLayout.oneOf(align, java.util.Set.of("left", "centre")));
        current.setCornerRadius(PortalLayout.radius(radius));
        current.setLogoSize(PortalLayout.oneOf(logoSize, java.util.Set.of("s", "m", "l")));
        current.setHeadingFont(PortalLayout.oneOf(headingFont,
                java.util.Set.of("sans", "serif", "mono", "rounded")));
        current.setDensity(PortalLayout.oneOf(density,
                java.util.Set.of("compact", "comfortable", "spacious")));
        return repository.save(current);
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
        // Currency: a blank code means "leave it alone", so a screen that does
        // not know about currency cannot silently reset an operator to
        // shillings by saving a form without the field.
        if (updated.getCurrencyCode() != null && !updated.getCurrencyCode().isBlank()) {
            current.setCurrencyCode(updated.getCurrencyCode().trim().toUpperCase());
            current.setCurrencySymbol(updated.getCurrencySymbol() == null
                    || updated.getCurrencySymbol().isBlank() ? null : updated.getCurrencySymbol().trim());
            current.setCurrencySuffix(updated.isCurrencySuffix());
            current.setCurrencyDecimals(Math.max(0, Math.min(4, updated.getCurrencyDecimals())));
        }
        // Country, on the same "blank means leave it" rule as currency.
        if (updated.getCountry() != null && !updated.getCountry().isBlank()) {
            current.setCountry(com.spalimited.hotspotbilling.service.i18n.Country
                    .of(updated.getCountry()).name());
        }
        // Blank clears the override and falls back to the country's default,
        // which is what an operator means when they empty the box.
        current.setPaymentBrand(updated.getPaymentBrand() == null
                || updated.getPaymentBrand().isBlank() ? null : updated.getPaymentBrand().trim());

        // Language, on the same "blank means leave it" rule and for the same
        // reason: a form that predates this field must not reset an operator
        // in Abidjan to English by saving something else.
        if (updated.getLanguage() != null && !updated.getLanguage().isBlank()) {
            current.setLanguage(com.spalimited.hotspotbilling.service.i18n.Language
                    .of(updated.getLanguage()).code());
            current.setFollowCustomerLanguage(updated.isFollowCustomerLanguage());
        }
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
