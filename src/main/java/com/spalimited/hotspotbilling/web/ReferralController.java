package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Referral;
import com.spalimited.hotspotbilling.domain.ReferralSettings;
import com.spalimited.hotspotbilling.service.ReferralService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Referral programme: public endpoints for the captive portal (get my code,
 * apply a code) and admin endpoints for settings and a leaderboard.
 */
@RestController
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referrals;

    // --- Public (captive portal) ---

    /** This phone's shareable code plus the current reward terms. */
    @GetMapping("/api/referral/{phone}")
    public Map<String, Object> myCode(@PathVariable String phone) {
        ReferralSettings s = referrals.settings();
        Referral r = referrals.codeFor(phone);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.isEnabled());
        out.put("code", r.getCode());
        out.put("successfulReferrals", r.getSuccessfulReferrals());
        out.put("referrerMinutes", s.getReferrerMinutes());
        out.put("refereeMinutes", s.getRefereeMinutes());
        return out;
    }

    public record ClaimRequest(String code) {
    }

    /** A new customer applies a friend's code before buying. */
    @PostMapping("/api/referral/{phone}/claim")
    public Map<String, Object> claim(@PathVariable String phone, @RequestBody ClaimRequest body) {
        return referrals.submitClaim(phone, body == null ? null : body.code());
    }

    // --- Admin ---

    @GetMapping("/api/admin/settings/referral")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public ReferralSettings getSettings() {
        return referrals.settings();
    }

    @PutMapping("/api/admin/settings/referral")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public ReferralSettings saveSettings(@RequestBody ReferralSettings in) {
        return referrals.saveSettings(in);
    }

    @GetMapping("/api/admin/referrals")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> overview() {
        return referrals.overview();
    }
}
