package com.spalimited.controlplane;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pretends to provision, for local development where there is no Docker host.
 * Logs what the real run would do and reports success after a short pause, so
 * the whole signup → provisioning → ready flow can be exercised end to end.
 */
@Component
@ConditionalOnProperty(name = "zidi.provisioner", havingValue = "DRY_RUN", matchIfMissing = true)
@Slf4j
public class DryRunProvisioner implements Provisioner {

    @Override
    public ProvisionResult provision(Tenant tenant, String ownerPassword) {
        log.info("[DRY RUN] Would provision tenant '{}' at {} (business: {}, owner: {})",
                tenant.getSlug(), tenant.getSubdomain(), tenant.getBusinessName(), tenant.getOwnerEmail());
        log.info("[DRY RUN]   OWNER_EMAIL={} OWNER_PASSWORD=*** ./deploy/new-tenant.sh {} {}",
                tenant.getOwnerEmail(), tenant.getSlug(), tenant.getSubdomain());
        try {
            Thread.sleep(2000); // stand in for container spin-up time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ProvisionResult.ok("Dry run — no stack was actually created.");
    }
}
