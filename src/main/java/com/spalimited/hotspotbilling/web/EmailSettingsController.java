package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.EmailSettings;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.EmailService;
import com.spalimited.hotspotbilling.service.EmailSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/** The operator's own SMTP server, for receipts, resets and reports. */
@RestController
@RequestMapping("/api/admin/settings/email")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class EmailSettingsController {

    private final EmailSettingsService service;
    private final EmailService email;
    private final AuditService audit;

    @GetMapping
    public Map<String, Object> get() {
        return service.describe();
    }

    public record EmailRequest(
            boolean enabled,
            String host,
            int port,
            String username,
            String password,
            String fromAddress,
            String fromName,
            boolean startTls) {
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody EmailRequest req, Principal principal) {
        service.save(EmailSettings.builder()
                .enabled(req.enabled())
                .host(req.host())
                .port(req.port())
                .username(req.username())
                .password(req.password())
                .fromAddress(req.fromAddress())
                .fromName(req.fromName())
                .startTls(req.startTls())
                .build(), principal.getName());
        audit.record(principal, "settings.email",
                "Updated SMTP settings (" + (req.enabled() ? "on" : "off") + ")");
        return service.describe();
    }

    public record TestRequest(@NotBlank @Email String to) {
    }

    @PostMapping("/test")
    public Map<String, Object> test(@Valid @RequestBody TestRequest req, Principal principal) {
        email.send(req.to(), "Test email from your billing system",
                "If you can read this, your SMTP settings are working. — Zidi");
        audit.record(principal, "settings.email.test", "Sent a test email to " + req.to());
        return Map.of("message", "Sent. Check that inbox (and the spam folder).");
    }
}
