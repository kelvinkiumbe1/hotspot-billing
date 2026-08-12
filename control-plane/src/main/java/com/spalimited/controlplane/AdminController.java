package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Platform (us) view of every tenant, and the levers to run them: retry a
 * failed provision, suspend a non-paying ISP. Guarded by a shared admin token
 * header — this is a small internal tool, not a public surface.
 */
@RestController
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
public class AdminController {

    private final TenantRepository tenants;
    private final SignupService signupService;

    @Value("${zidi.admin-token:}")
    private String adminToken;

    private void authorize(String token) {
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin token required");
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        return tenants.findAllByOrderByCreatedAtDesc().stream().map(t -> Map.<String, Object>of(
                "slug", t.getSlug(),
                "subdomain", t.getSubdomain(),
                "businessName", t.getBusinessName(),
                "ownerEmail", t.getOwnerEmail(),
                "status", t.getStatus().name(),
                "statusDetail", t.getStatusDetail() == null ? "" : t.getStatusDetail(),
                "createdAt", String.valueOf(t.getCreatedAt()))).toList();
    }

    @PostMapping("/{slug}/retry")
    public Map<String, String> retry(@PathVariable String slug,
                                     @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        signupService.retry(slug);
        return Map.of("message", "Retrying provisioning for " + slug);
    }

    @PostMapping("/{slug}/suspend")
    public Map<String, String> suspend(@PathVariable String slug,
                                       @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        authorize(token);
        Tenant tenant = tenants.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such tenant"));
        tenant.setStatus(Tenant.Status.SUSPENDED);
        tenant.setStatusDetail("Suspended by platform admin");
        tenants.save(tenant);
        return Map.of("message", slug + " suspended");
    }
}
