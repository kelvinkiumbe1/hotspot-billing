package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a signup into a provisioning tenant. Validation is strict because the
 * slug becomes a subdomain, a container name and a shell argument: only lower
 * alphanumerics and single dashes, nothing reserved, so it can never inject a
 * flag or a shell metacharacter downstream.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignupService {

    private final TenantRepository tenants;
    private final ProvisioningWorker worker;

    @Value("${zidi.base-domain}")
    private String baseDomain;

    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
    private static final Set<String> RESERVED = Set.of(
            "www", "api", "admin", "app", "mail", "demo", "status", "billing",
            "dashboard", "portal", "zidi", "test", "staging", "control", "edge");

    public record SignupRequest(String businessName, String slug, String ownerName,
                                String ownerEmail, String password) {
    }

    // Not @Transactional: the tenant must be committed before the async worker
    // (a separate thread, separate transaction) tries to load it, or it races
    // and finds nothing. repository.save commits on its own.
    public Tenant signup(SignupRequest req) {
        String slug = req.slug() == null ? "" : req.slug().trim().toLowerCase();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "Choose a name of 2–40 lowercase letters, digits and dashes (not starting or ending with a dash).");
        }
        if (RESERVED.contains(slug)) {
            throw new IllegalArgumentException("That name is reserved — pick another.");
        }
        if (tenants.existsBySlug(slug)) {
            throw new IllegalArgumentException("That name is taken — pick another.");
        }
        String subdomain = slug + "." + baseDomain;
        if (tenants.existsBySubdomain(subdomain)) {
            throw new IllegalArgumentException("That address is taken — pick another.");
        }
        // One account per email — a repeat signup should sign in instead. Keeps
        // the platform-admin list clean and stops the 14-day trial being reset
        // over and over from the same address.
        String email = req.ownerEmail() == null ? "" : req.ownerEmail().trim().toLowerCase();
        if (tenants.existsByOwnerEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists — sign in instead.");
        }

        Tenant tenant = tenants.save(Tenant.builder()
                .slug(slug)
                .subdomain(subdomain)
                .businessName(req.businessName() == null ? slug : req.businessName().trim())
                .ownerName(req.ownerName() == null ? null : req.ownerName().trim())
                .ownerEmail(email)
                .status(Tenant.Status.PROVISIONING)
                .statusDetail("Setting up your account…")
                .build());

        worker.provision(tenant.getId(), req.password());
        return tenant;
    }

    /**
     * Re-run provisioning for a tenant that failed (admin action). The owner's
     * password isn't stored, so a retry provisions without it — the script then
     * generates one, which the admin reads from the logs.
     */
    public void retry(String slug) {
        Tenant tenant = tenants.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("No such tenant"));
        tenant.setStatus(Tenant.Status.PROVISIONING);
        tenant.setStatusDetail("Retrying…");
        tenants.save(tenant);
        worker.provision(tenant.getId(), null);
    }
}
