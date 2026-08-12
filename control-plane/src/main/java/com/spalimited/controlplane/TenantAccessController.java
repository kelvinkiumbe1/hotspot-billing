package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Backs the sign-in page: given a tenant's address (or an owner's email),
 * returns where to send them and whether that dashboard is answering yet. In
 * LOCAL mode (tenants are plain processes that don't survive a control-plane
 * restart) it also wakes a sleeping tenant on demand, so the sign-in page can
 * wait for it to come up. In production the stack is always running, so
 * `ready` is simply true.
 */
@RestController
@RequiredArgsConstructor
public class TenantAccessController {

    private final TenantRepository tenants;
    /** Present only when zidi.provisioner=LOCAL. */
    private final Optional<LocalProvisioner> localProvisioner;

    @Value("${zidi.base-domain}")
    private String baseDomain;

    @GetMapping("/api/tenant/{slug}/location")
    public Map<String, Object> location(@PathVariable String slug) {
        Tenant tenant = tenants.findBySlug(slug == null ? "" : slug.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("No account found at that address."));
        return describe(tenant, false);
    }

    /**
     * Resolve a sign-in by either an owner's email or a workspace address, so
     * the sign-in page can accept whichever the ISP remembers. Email only maps
     * an OWNER to their workspace (staff logins live in the tenant, not here);
     * everything else is treated as an address.
     */
    @GetMapping("/api/tenant/resolve")
    public Map<String, Object> resolve(@RequestParam String q) {
        String v = q == null ? "" : q.trim().toLowerCase();
        boolean byEmail = v.contains("@");
        Tenant tenant;
        if (byEmail) {
            tenant = tenants.findByOwnerEmail(v)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No account found for that email. Enter your workspace address, or start free."));
        } else {
            String slug = v.replace("." + baseDomain, "").replaceAll("[^a-z0-9-]", "");
            tenant = tenants.findBySlug(slug)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No account found at that address — check it, or start free."));
        }
        return describe(tenant, byEmail);
    }

    private Map<String, Object> describe(Tenant tenant, boolean byEmail) {
        boolean ready = true;
        if (localProvisioner.isPresent() && tenant.getLocalPort() != null) {
            ready = localProvisioner.get().isUp(tenant);
            if (!ready) {
                localProvisioner.get().startIfDown(tenant); // fire-and-forget; caller polls
            }
        }
        return Map.of(
                "slug", tenant.getSlug(),
                "url", tenant.getUrl(),
                "status", tenant.getStatus().name(),
                "ready", ready,
                // Only echo the email back (to pre-fill the tenant login) when
                // they signed in by email — and only the owner's, which we already store.
                "email", byEmail ? tenant.getOwnerEmail() : "");
    }
}
