package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.HotspotSettings;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.HotspotSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/** Hotspot lifecycle: post-purchase redirect and unused-voucher expiry. */
@RestController
@RequestMapping("/api/admin/settings/hotspot")
@RequiredArgsConstructor
public class HotspotSettingsController {

    private final HotspotSettingsService service;
    private final AuditService audit;

    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    public HotspotSettings get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    public HotspotSettings update(@RequestBody HotspotSettings body, Principal principal) {
        HotspotSettings saved = service.update(body);
        audit.record(principal, "settings.hotspot",
                "Updated hotspot lifecycle (redirect "
                        + (saved.getPostPurchaseRedirect() == null ? "off" : "set")
                        + ", unused expiry " + saved.getUnusedVoucherExpiryDays() + "d)");
        return saved;
    }
}
