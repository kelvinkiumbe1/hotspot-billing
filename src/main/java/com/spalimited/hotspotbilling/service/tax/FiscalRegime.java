package com.spalimited.hotspotbilling.service.tax;

import com.spalimited.hotspotbilling.domain.TaxInvoice;

import java.math.BigDecimal;

/**
 * One country's rules for turning a sale into a receipt the state recognises.
 *
 * <p>This is a legal blocker rather than a feature. An ISP in Lagos cannot issue
 * a receipt at all without filing it with FIRS, so a billing system that only
 * speaks to KRA cannot be sold in Nigeria whatever else it does. Each regime
 * differs in the identifier it demands, the rate, the numbering, and where a
 * customer goes to check a receipt is real.
 *
 * <p>What no implementation here does is invent an endpoint. The submission call
 * needs a registered device and credentials from the authority, and guessing at
 * the wire format would produce something that looks finished and files nothing.
 * So each regime signs locally in a dry run — the shape of the document, the
 * numbering and the verification link are all real — and says plainly that the
 * live call is not wired.
 */
public interface FiscalRegime {

    /** Stored on the invoice, so a reprint knows which authority issued it. */
    String code();

    /** What an operator calls this in their own country. */
    String label();

    /**
     * What the tax identifier is called here.
     *
     * <p>Asking a Nigerian operator for their "KRA PIN" is the kind of detail
     * that tells them the product was not built for them.
     */
    String taxIdLabel();

    /** The headline VAT rate, as a percentage. Only a default; the operator sets it. */
    BigDecimal defaultVatRate();

    /**
     * Fills in the fiscal fields.
     *
     * <p>Deterministic: the same invoice signs to the same number twice, so a
     * retry cannot mint a second receipt for one sale.
     */
    void sign(TaxInvoice invoice, String taxId, String deviceId);

    /** Whether a live filing is possible, as opposed to a local dry run. */
    default boolean canFileLive() {
        return false;
    }
}
