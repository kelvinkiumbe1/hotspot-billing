package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.LoyaltySettings;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.LoyaltyService;
import com.spalimited.hotspotbilling.service.PhoneOwnership;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * The loyalty programme. Balance and redemption are public (the portal has
 * only a phone number to go on); redemption is safe because the reward is
 * SMSed to that phone, never returned in the response. Settings are
 * owner-only.
 */
@RestController
@RequiredArgsConstructor
public class LoyaltyController {

    private final LoyaltyService loyalty;
    private final AuditService audit;
    private final PhoneOwnership ownership;

    private static final String PURPOSE = "LOYALTY";

    // --- Public (captive portal) ---

    @GetMapping("/api/loyalty/{phone}")
    public Map<String, Object> balance(
            @PathVariable @com.spalimited.hotspotbilling.config.Phone String phone,
            @RequestHeader(name = "X-Phone-Proof", required = false) String proof) {
        ownership.check(phone, PURPOSE, proof);
        return loyalty.balance(phone);
    }

    public record RedeemRequest(@Min(1) @Max(10080) int minutes) {
    }

    @PostMapping("/api/loyalty/{phone}/redeem")
    public Map<String, Object> redeem(
            @PathVariable @com.spalimited.hotspotbilling.config.Phone String phone,
            @Valid @RequestBody RedeemRequest req,
            @RequestHeader(name = "X-Phone-Proof", required = false) String proof) {
        // The reward itself only ever reaches the owning handset by SMS, so
        // nobody else could steal it -- but they could still spend somebody
        // else's points and leave them with nothing to show for it.
        ownership.require(phone, PURPOSE, proof);
        return loyalty.redeem(phone, req.minutes());
    }

    // --- Admin settings ---

    @GetMapping("/api/admin/settings/loyalty")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public LoyaltySettings get() {
        return loyalty.settings();
    }

    @PutMapping("/api/admin/settings/loyalty")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public LoyaltySettings update(@RequestBody LoyaltySettings body, Principal principal) {
        LoyaltySettings saved = loyalty.saveSettings(body);
        audit.record(principal, "settings.loyalty",
                "Updated loyalty programme (" + (saved.isEnabled() ? "on" : "off") + ")");
        return saved;
    }
}
