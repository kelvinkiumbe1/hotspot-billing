package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Runs the slow provisioning off the request thread. A separate bean, not a
 * method on SignupService, because Spring's @Async only applies across a bean
 * boundary — a self-call would silently run synchronously and hang the signup
 * request for the minute-or-two a real container takes to come up.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProvisioningWorker {

    private final TenantRepository tenants;
    private final Provisioner provisioner;
    private final MailService mailService;

    /**
     * @param ownerPassword held only in memory on this worker thread for the
     *                      length of provisioning; never persisted anywhere in
     *                      the control plane.
     */
    @Async
    public void provision(Long tenantId, String ownerPassword) {
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.warn("Provisioning skipped — tenant {} vanished", tenantId);
            return;
        }
        Provisioner.ProvisionResult result = provisioner.provision(tenant, ownerPassword);
        mark(tenantId, result);
    }

    @Transactional
    public void mark(Long tenantId, Provisioner.ProvisionResult result) {
        tenants.findById(tenantId).ifPresent(tenant -> {
            if (result.success()) {
                tenant.setStatus(Tenant.Status.ACTIVE);
                tenant.setReadyAt(Instant.now());
                tenant.setStatusDetail("Your account is ready.");
                tenants.save(tenant);
                mailService.sendAccountReady(tenant);
            } else {
                tenant.setStatus(Tenant.Status.FAILED);
                tenant.setStatusDetail(result.detail());
                tenants.save(tenant);
            }
        });
    }
}
