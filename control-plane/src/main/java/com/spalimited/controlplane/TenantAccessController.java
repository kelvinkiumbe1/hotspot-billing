package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Backs the sign-in page: given a tenant's address, returns where to send them
 * and whether that dashboard is answering yet. In LOCAL mode (tenants are
 * plain processes that don't survive a control-plane restart) it also wakes a
 * sleeping tenant on demand, so the sign-in page can wait for it to come up.
 * In production the stack is always running, so `ready` is simply true.
 */
@RestController
@RequiredArgsConstructor
public class TenantAccessController {

    private final TenantRepository tenants;
    /** Present only when zidi.provisioner=LOCAL. */
    private final Optional<LocalProvisioner> localProvisioner;

    @GetMapping("/api/tenant/{slug}/location")
    public Map<String, Object> location(@PathVariable String slug) {
        Tenant tenant = tenants.findBySlug(slug == null ? "" : slug.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("No account found at that address."));

        boolean ready = true;
        if (localProvisioner.isPresent() && tenant.getLocalPort() != null) {
            ready = localProvisioner.get().isUp(tenant);
            if (!ready) {
                localProvisioner.get().startIfDown(tenant); // fire-and-forget; caller polls
            }
        }
        return Map.of(
                "url", tenant.getUrl(),
                "status", tenant.getStatus().name(),
                "ready", ready);
    }
}
