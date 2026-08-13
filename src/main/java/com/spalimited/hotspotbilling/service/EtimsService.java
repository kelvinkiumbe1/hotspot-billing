package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.TaxInvoice;
import com.spalimited.hotspotbilling.repository.TaxInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * KRA eTIMS fiscalisation. Every completed sale is turned into a signed tax
 * invoice. This is a seam, like the M-Pesa one: a {@code DRYRUN} provider signs
 * invoices locally (sandbox behaviour) so the whole flow — record, sign, store,
 * display — works end to end, while a real {@code KRA} provider (device
 * registration + OSCU/VSCU calls) is dropped in later without touching callers.
 *
 * <p>Disabled by default ({@code etims.enabled=false}); until an operator wires
 * their KRA credentials, sales simply aren't fiscalised and callers no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EtimsService {

    private final TaxInvoiceRepository invoices;

    @Value("${etims.enabled:false}")
    private boolean enabled;

    /** DRYRUN (local sandbox signer) or KRA (real, not yet wired). */
    @Value("${etims.provider:DRYRUN}")
    private String provider;

    /** The signing device's control-unit id, as issued by KRA on registration. */
    @Value("${etims.control-unit:KRACU0100000000}")
    private String controlUnit;

    /**
     * Records and fiscalises a sale. Never throws into the sale path: any
     * failure is logged and the invoice is left FAILED for retry/inspection.
     */
    @Transactional
    public TaxInvoice recordSale(TaxInvoice.Source source, String phone, String description, BigDecimal amount) {
        if (!enabled) {
            return null; // fiscalisation off until KRA is configured
        }
        TaxInvoice invoice = invoices.save(TaxInvoice.builder()
                .source(source)
                .customerPhone(phone)
                .description(description)
                .amount(amount == null ? BigDecimal.ZERO : amount)
                .status(TaxInvoice.Status.PENDING)
                .build());
        try {
            if ("DRYRUN".equalsIgnoreCase(provider)) {
                sign(invoice);
            } else {
                // Real KRA submission (OSCU/VSCU) goes here once credentials and
                // device registration exist. Until then, leave it PENDING.
                log.warn("eTIMS provider '{}' is not wired yet — invoice {} left PENDING", provider, invoice.getId());
            }
        } catch (Exception e) {
            invoice.setStatus(TaxInvoice.Status.FAILED);
            log.warn("eTIMS signing failed for invoice {}: {}", invoice.getId(), e.getMessage());
        }
        return invoices.save(invoice);
    }

    /** Sandbox signer: fills KRA-style fiscal fields deterministically. */
    private void sign(TaxInvoice invoice) {
        long id = invoice.getId();
        String sig = Long.toHexString(Math.abs((long) Objects.hash(id, invoice.getAmount(),
                invoice.getCustomerPhone(), controlUnit))).toUpperCase(Locale.ROOT);
        invoice.setKraInvoiceNumber(String.format(Locale.ROOT, "KRA-%08d", id));
        invoice.setControlUnitNumber(controlUnit);
        invoice.setSignature(sig);
        invoice.setQrData("https://itax.kra.go.ke/KRA-Portal/invoiceChk.htm?invoiceNo=" + sig);
        invoice.setStatus(TaxInvoice.Status.SIGNED);
        invoice.setSignedAt(Instant.now());
    }
}
