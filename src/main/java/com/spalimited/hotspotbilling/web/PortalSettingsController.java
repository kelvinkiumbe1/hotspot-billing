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
    private final NotificationService notifications;
    private final CustomPlanService customPlanService;
    private final VoucherService voucherService;
    private final TrialClaimRepository trialClaims;
    private final FileStorageService storage;
    private final AuditService audit;

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
        out.put("loyaltyEnabled", loyaltyService.settings().isEnabled());
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
            @Pattern(regexp = "254\\d{9}", message = "Phone must be in 2547XXXXXXXX format")
            String phoneNumber) {
    }

    // --- Admin: branding ---

    @PreAuthorize("hasAuthority('SETTINGS')")
    @GetMapping("/api/admin/portal-settings")
    public PortalSettings adminSettings() {
        return portalSettings.settings();
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
            @Min(1) @Max(1440) int trialMinutes) {
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
                .build());
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
