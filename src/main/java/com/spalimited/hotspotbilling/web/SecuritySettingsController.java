package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.SecuritySettings;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.SecuritySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/** Security policy: passkey enforcement, session length and lockout. */
@RestController
@RequestMapping("/api/admin/settings/security")
@RequiredArgsConstructor
public class SecuritySettingsController {

    private final SecuritySettingsService service;
    private final AuditService audit;

    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    public SecuritySettings get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    public SecuritySettings update(@RequestBody SecuritySettings body, Principal principal) {
        SecuritySettings saved = service.update(body);
        audit.record(principal, "settings.security",
                "Updated security policy (passkeys "
                        + (saved.isRequirePasskeys() ? "required" : "optional")
                        + ", session " + saved.getSessionTimeoutHours() + "h, lockout at "
                        + saved.getMaxLoginAttempts() + " attempts)");
        return saved;
    }
}
