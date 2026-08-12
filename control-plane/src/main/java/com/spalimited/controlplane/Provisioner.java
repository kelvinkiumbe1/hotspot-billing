package com.spalimited.controlplane;

/**
 * Stands up a tenant's isolated stack (its own container + database + subdomain
 * route). Two implementations: a dry run for local development, and one that
 * actually invokes deploy/new-tenant.sh on the Docker host. The seam keeps all
 * the signup/registry/status logic testable without Docker.
 */
public interface Provisioner {

    /**
     * @param ownerPassword the bootstrap password the owner chose at signup,
     *                      passed straight to provisioning and never stored in
     *                      the control plane; may be null for an admin retry.
     */
    ProvisionResult provision(Tenant tenant, String ownerPassword);

    record ProvisionResult(boolean success, String detail) {
        static ProvisionResult ok(String detail) {
            return new ProvisionResult(true, detail);
        }

        static ProvisionResult failed(String detail) {
            return new ProvisionResult(false, detail);
        }
    }
}
