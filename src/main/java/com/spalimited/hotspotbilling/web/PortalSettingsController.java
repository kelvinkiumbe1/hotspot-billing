package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.TrialClaim;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.TrialClaimRepository;
import com.spalimited.hotspotbilling.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captive-portal branding, message templates and the free-trial offer.
 * The portal reads its branding from a public endpoint; everything else
 * is admin-only (HTTP Basic, ADMIN role).
 */
@RestController
@RequiredArgsConstructor
public class PortalSettingsController {

    private final PortalSettingsService portalSettings;
    private final HotspotSettingsService hotspotSettings;
    private final LoyaltyService loyaltyService;
    private final PaymentGatewayService paymentGatewayService;
    private final NotificationService notifications;
    private final CustomPlanService customPlanService;
    private final VoucherService voucherService;
    private final TrialClaimRepository trialClaims;
    private final FileStorageService storage;
    private final AuditService audit;
    private final PortalCopyService portalCopy;

    // --- Public: what the portal needs to brand itself ---

    @GetMapping("/api/portal-settings")
    public Map<String, Object> publicSettings() {
        PortalSettings s = portalSettings.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessName", s.getBusinessName());
        out.put("headline", s.getHeadline());
        out.put("subheadline", s.getSubheadline());
        out.put("logoUrl", s.getLogoFilename() != null ? "/api/uploads/" + s.getLogoFilename() : null);
        out.put("backgroundColor", s.getBackgroundColor());
        out.put("accentColor", s.getAccentColor());
        out.put("supportPhone", s.getSupportPhone());
        out.put("termsText", s.getTermsText());
        out.put("trialEnabled", s.isTrialEnabled());
        out.put("trialMinutes", s.getTrialMinutes());
        out.put("postPurchaseRedirect", hotspotSettings.postPurchaseRedirect());
        out.put("portalTemplate", hotspotSettings.portalTemplate());
        out.put("defaultLanguage", hotspotSettings.defaultLanguage());
        out.put("loyaltyEnabled", loyaltyService.settings().isEnabled());
        // How the operator has arranged the page, and any wording they have
        // rewritten. Both go out with the branding rather than on endpoints of
        // their own: the portal cannot paint a single screen without them, and a
        // second round trip is a second chance to show the wrong thing first.
        out.put("layout", PortalLayout.describe(s));
        // Overrides only, for every language. Never the defaults -- the portal
        // already ships those, and sending them too would mean two copies to keep
        // in step and a portal that quietly kept saying last release's words.
        out.put("copy", portalCopy.all());
        out.put("codeVerifyEnabled", paymentGatewayService.transactionStatusAvailable());
        // How to write money. The portal prints prices on every screen, so it
        // needs this as early as it needs the business name — otherwise it
        // renders a Nigerian operator's plans in shillings for a moment and
        // then corrects itself, which is worse than either.
        out.put("currency", Map.of(
                "code", s.getCurrencyCode() == null ? "KES" : s.getCurrencyCode(),
                "symbol", s.getCurrencySymbol() == null ? "" : s.getCurrencySymbol(),
                "suffix", s.isCurrencySuffix(),
                "decimals", s.getCurrencyDecimals()));
        // Whether the portal may follow the customer's own phone. When an
        // operator has turned that off they mean it, and the browser must not
        // quietly override them on the one screen they cannot see.
        out.put("followCustomerLanguage", s.isFollowCustomerLanguage());
        // What paying is called here. The portal prints this on nearly every
        // screen, so it has to arrive with the branding rather than after it —
        // otherwise a Ghanaian customer reads "M-Pesa" for a moment before it
        // corrects itself, which is worse than either.
        out.put("country", s.getCountry());
        out.put("paymentBrand", paymentBrand(s));
        return out;
    }

    /** One free trial voucher per phone number, when the offer is on. */
    @PostMapping("/api/trial")
    @Transactional
    public Map<String, Object> claimTrial(@Valid @RequestBody TrialRequest request) {
        PortalSettings s = portalSettings.settings();
        if (!s.isTrialEnabled()) {
            throw new IllegalStateException("Free trials are not available right now");
        }
        trialClaims.findById(request.phoneNumber()).ifPresent(claim -> {
            throw new IllegalStateException("This number has already used its free trial");
        });
        Voucher voucher = voucherService.issueCustom(
                customPlanService.systemPlan(customPlanService.settings()),
                request.phoneNumber(), s.getTrialMinutes(), null, null, "trial");
        trialClaims.save(TrialClaim.builder()
                .phoneNumber(request.phoneNumber())
                .voucherCode(voucher.getCode())
                .build());
        notifications.send(NotificationTemplate.Key.TRIAL_ISSUED, request.phoneNumber(), Map.of(
                "business", s.getBusinessName(),
                "code", voucher.getCode(),
                "minutes", String.valueOf(s.getTrialMinutes())));
        return Map.of("code", voucher.getCode(), "minutes", s.getTrialMinutes());
    }

    public record TrialRequest(
            @com.spalimited.hotspotbilling.config.Phone
            String phoneNumber) {
    }

    // --- Admin: branding ---

    @PreAuthorize("hasAuthority('SETTINGS')")
    @GetMapping("/api/admin/portal-settings")
    public PortalSettings adminSettings() {
        return portalSettings.settings();
    }

    /** The operator's own wording if they set one, else the country's default. */
    static String paymentBrand(PortalSettings s) {
        if (s.getPaymentBrand() != null && !s.getPaymentBrand().isBlank()) {
            return s.getPaymentBrand().trim();
        }
        return com.spalimited.hotspotbilling.service.i18n.Country
                .of(s.getCountry()).paymentBrand();
    }

    /** Every country the picker offers, with what each one implies. */
    @GetMapping("/api/countries")
    public List<Map<String, Object>> countries() {
        return com.spalimited.hotspotbilling.service.i18n.Country.describeAll();
    }

    public record SettingsRequest(
            @NotBlank String businessName,
            String headline,
            String subheadline,
            String backgroundColor,
            String accentColor,
            String supportPhone,
            String termsText,
            boolean trialEnabled,
            @Min(1) @Max(1440) int trialMinutes,
            // Money and language. Both were settings the system already
            // honoured and nothing could set, so every operator was stuck on
            // shillings and English whatever the database could hold.
            String currencyCode,
            String currencySymbol,
            boolean currencySuffix,
            @Min(0) @Max(4) int currencyDecimals,
            String language,
            boolean followCustomerLanguage,
            // Where the operator is, and what paying is called there. The
            // second defaults from the first but can be overruled — an
            // operator knows their own market better than a table does.
            String country,
            String paymentBrand) {
    }

    @PreAuthorize("hasAuthority('SETTINGS')")
    @PutMapping("/api/admin/portal-settings")
    public PortalSettings update(@Valid @RequestBody SettingsRequest request, Principal principal) {
        audit.record(principal, "portal.settings", "Updated captive-portal branding");
        return portalSettings.update(PortalSettings.builder()
                .businessName(request.businessName())
                .headline(request.headline())
                .subheadline(request.subheadline())
                .backgroundColor(request.backgroundColor())
                .accentColor(request.accentColor())
                .supportPhone(request.supportPhone())
                .termsText(request.termsText())
                .trialEnabled(request.trialEnabled())
                .trialMinutes(request.trialMinutes())
                .currencyCode(request.currencyCode())
                .currencySymbol(request.currencySymbol())
                .currencySuffix(request.currencySuffix())
                .currencyDecimals(request.currencyDecimals())
                .language(request.language())
                .followCustomerLanguage(request.followCustomerLanguage())
                .country(request.country())
                .paymentBrand(request.paymentBrand())
                .build());
    }

    /** The blocks an operator may move, so the admin never has to hardcode them. */
    @PreAuthorize("hasAuthority('SETTINGS')")
    @GetMapping("/api/admin/portal-settings/layout")
    public Map<String, Object> layout() {
        Map<String, Object> out = new LinkedHashMap<>(PortalLayout.describe(portalSettings.settings()));
        out.put("blocks", PortalLayout.BLOCKS);
        out.put("required", PortalLayout.REQUIRED);
        return out;
    }

    public record LayoutRequest(
            List<String> order,
            List<String> hidden,
            String align,
            @Min(0) @Max(24) Integer radius,
            String logoSize,
            String headingFont,
            String density) {
    }

    @PreAuthorize("hasAuthority('SETTINGS')")
    @PutMapping("/api/admin/portal-settings/layout")
    public Map<String, Object> saveLayout(@Valid @RequestBody LayoutRequest request,
                                          Principal principal) {
        portalSettings.updateLayout(request.order(), request.hidden(), request.align(),
                request.radius(), request.logoSize(), request.headingFont(), request.density());
        audit.record(principal, "portal.layout", "Rearranged the captive portal");
        return layout();
    }

    // --- The operator's own wording ---

    /**
     * What has been rewritten, by language.
     *
     * <p>Only the overrides. The admin screen holds the built-in defaults itself
     * -- it renders the same portal string table -- so sending them from here
     * would be a second copy able to disagree with the first.
     */
    @PreAuthorize("hasAuthority('SETTINGS')")
    @GetMapping("/api/admin/portal-copy")
    public Map<String, Map<String, String>> copy() {
        return portalCopy.all();
    }

    @PreAuthorize("hasAuthority('SETTINGS')")
    @PutMapping("/api/admin/portal-copy/{language}")
    public Map<String, Object> saveCopy(@PathVariable String language,
                                        @RequestBody Map<String, String> edits,
                                        Principal principal) {
        int changed = portalCopy.save(language, edits, principal.getName());
        audit.record(principal, "portal.copy",
                "Edited " + changed + " line(s) of portal wording in " + language);
        return Map.of("language", language, "changed", changed);
    }

    /** Puts one language back to how it shipped. */
    @PreAuthorize("hasAuthority('SETTINGS')")
    @PostMapping("/api/admin/portal-copy/{language}/reset")
    public Map<String, Object> resetCopy(@PathVariable String language, Principal principal) {
        int removed = portalCopy.reset(language);
        audit.record(principal, "portal.copy",
                "Restored the original portal wording in " + language);
        return Map.of("language", language, "restored", removed);
    }

    @PreAuthorize("hasAuthority('SETTINGS')")
    @PostMapping(value = "/api/admin/portal-settings/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PortalSettings uploadLogo(@RequestParam MultipartFile logo, Principal principal) throws IOException {
        if (logo == null || logo.isEmpty()) {
            throw new IllegalArgumentException("Choose a logo image to upload");
        }
        audit.record(principal, "portal.logo", "Uploaded a new portal logo");
        return portalSettings.setLogo(storage.storeImage(logo));
    }

    // --- Admin: message templates ---

    @PreAuthorize("hasAuthority('SETTINGS')")
    @GetMapping("/api/admin/templates")
    public List<NotificationTemplate> templates() {
        return notifications.all();
    }

    public record TemplateRequest(@NotBlank String body, boolean enabled) {
    }

    @PreAuthorize("hasAuthority('SETTINGS')")
    @PutMapping("/api/admin/templates/{key}")
    public NotificationTemplate updateTemplate(@PathVariable NotificationTemplate.Key key,
                                               @Valid @RequestBody TemplateRequest request,
                                               Principal principal) {
        audit.record(principal, "template.update", "Edited the " + key + " message");
        return notifications.update(key, request.body(), request.enabled());
    }
}
