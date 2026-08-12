package com.spalimited.controlplane;

/**
 * Sends the M-Pesa STK push that collects Zidi's platform fee from an ISP.
 * A seam, like the provisioner: a dry run for local development and one that
 * talks to Daraja in production. Zidi's own Daraja credentials live only here
 * in the control plane, never in a tenant.
 */
public interface PlatformStk {

    StkResult push(PlatformInvoice invoice);

    record StkResult(boolean initiated, String checkoutId, String detail) {
        static StkResult ok(String checkoutId, String detail) {
            return new StkResult(true, checkoutId, detail);
        }

        static StkResult failed(String detail) {
            return new StkResult(false, null, detail);
        }
    }
}
