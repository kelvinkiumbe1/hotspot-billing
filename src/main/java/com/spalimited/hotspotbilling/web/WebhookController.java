package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Webhook;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.WebhookService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Developer settings: outbound webhooks. Owner-only. */
@RestController
@RequestMapping("/api/admin/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhooks;
    private final AuditService audit;

    /** The event names a webhook can subscribe to, for the UI checklist. */
    @GetMapping("/events")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public List<String> events() {
        return WebhookService.EVENTS;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    public List<Map<String, Object>> list() {
        return webhooks.list();
    }

    public record CreateRequest(@NotBlank String label, @NotBlank String url, String secret, List<String> events) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateRequest req, Principal principal) {
        Webhook w = webhooks.create(req.label(), req.url(), req.secret(), req.events(), principal.getName());
        audit.record(principal, "webhook.create", "Added webhook '" + w.getLabel() + "' -> " + w.getUrl());
        // Return the secret once so the consumer can configure signature checks.
        return Map.of("id", w.getId(), "label", w.getLabel(), "url", w.getUrl(),
                "secret", w.getSecret(), "events", w.getEvents().split(","));
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> test(@PathVariable Long id) {
        webhooks.sendTest(id);
        return Map.of("sent", true);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SETTINGS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Principal principal) {
        webhooks.delete(id);
        audit.record(principal, "webhook.delete", "Removed webhook #" + id);
    }
}
