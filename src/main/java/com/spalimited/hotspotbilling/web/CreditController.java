package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CreditAdvance;
import com.spalimited.hotspotbilling.domain.CreditSettings;
import com.spalimited.hotspotbilling.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Lipa Baadaye": the captive portal asks whether this number may take a pass
 * on credit and takes one; the admin console sets the terms and watches what
 * is out on loan.
 */
@RestController
@RequiredArgsConstructor
public class CreditController {

    private final CreditService credit;

    // --- Public (captive portal) ---

    /** Whether this number can pay later, and what it owes already. */
    @GetMapping("/api/credit/{phone}")
    public Map<String, Object> check(@PathVariable String phone) {
        CreditService.Eligibility e = credit.eligibility(phone);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", e.enabled());
        out.put("eligible", e.eligible());
        out.put("reason", e.reason());
        out.put("limit", e.limit());
        out.put("outstanding", e.outstanding());
        return out;
    }

    public record TakeRequest(Long planId) {
    }

    @PostMapping("/api/credit/{phone}/take")
    public Map<String, Object> take(@PathVariable String phone, @RequestBody TakeRequest body) {
        return credit.take(phone, body == null ? null : body.planId());
    }

    // --- Admin ---

    @GetMapping("/api/admin/credit")
    @PreAuthorize("hasAuthority('FINANCE')")
    public Map<String, Object> overview() {
        return credit.overview();
    }

    @GetMapping("/api/admin/settings/credit")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public CreditSettings getSettings() {
        return credit.settings();
    }

    @PutMapping("/api/admin/settings/credit")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public CreditSettings saveSettings(@RequestBody CreditSettings in) {
        return credit.saveSettings(in);
    }

    @PostMapping("/api/admin/credit/{id}/write-off")
    @PreAuthorize("hasAuthority('FINANCE')")
    public CreditAdvance writeOff(@PathVariable Long id, Principal principal) {
        return credit.writeOff(id, principal != null ? principal.getName() : "system");
    }
}
