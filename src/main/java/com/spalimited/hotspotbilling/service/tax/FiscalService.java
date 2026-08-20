package com.spalimited.hotspotbilling.service.tax;

import com.spalimited.hotspotbilling.domain.TaxInvoice;
import com.spalimited.hotspotbilling.domain.TaxSettings;
import com.spalimited.hotspotbilling.repository.TaxInvoiceRepository;
import com.spalimited.hotspotbilling.service.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turning a completed sale into a receipt the tax authority recognises.
 *
 * <p>Replaces the eTIMS-only version. That one hard-coded Kenya into its column
 * names and its numbering, which meant Zidi could not legally be sold in Nigeria
 * or Tanzania however good the rest of it was — an operator there cannot issue a
 * receipt at all without filing it.
 *
 * <p>Two rules carried over from the eTIMS version because they matter more than
 * the fiscalisation does. It never throws into the sale path: a tax authority
 * being unreachable must not stop a customer buying WiFi, so a failure leaves the
 * invoice FAILED for retry and the sale completes. And it does nothing at all
 * while switched off, which is how it ships.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FiscalService {

    private final TaxInvoiceRepository invoices;
    private final TaxService taxService;

    /**
     * DRYRUN signs locally; LIVE would file with the authority.
     *
     * <p>No regime can file live yet. Every one of them needs a registered device
     * and credentials, and inventing the wire format would produce something that
     * looks finished and files nothing — which is worse than an honest gap,
     * because an operator would find out at their first audit.
     */
    @Value("${fiscal.mode:DRYRUN}")
    private String mode;

    /** The signing device's id, as issued by the authority on registration. */
    @Value("${fiscal.device-id:ZIDI-DRYRUN-01}")
    private String deviceId;

    @Value("${fiscal.enabled:false}")
    private boolean enabled;

    /** The regime this operator files under, from their settings. */
    public FiscalRegime regime() {
        return FiscalRegimes.byCode(taxService.settings().getRegime());
    }

    /**
     * Records and fiscalises a sale.
     *
     * <p>Returns null when fiscalisation is off, which is the normal state until
     * an operator has registered with their authority.
     */
    @Transactional
    public TaxInvoice recordSale(TaxInvoice.Source source, String phone, String description,
                                 BigDecimal amount) {
        if (!enabled) {
            return null;
        }
        TaxSettings settings = taxService.settings();
        FiscalRegime regime = FiscalRegimes.byCode(settings.getRegime());
        BigDecimal gross = amount == null ? BigDecimal.ZERO : amount;

        TaxInvoice invoice = invoices.save(TaxInvoice.builder()
                .source(source)
                .customerPhone(phone)
                .description(description)
                .amount(gross)
                .regime(regime.code())
                .status(TaxInvoice.Status.PENDING)
                .build());
        try {
            // The rate is stamped on the invoice, not read back from settings at
            // display time: a rate change would otherwise rewrite history and a
            // reprint would stop matching the customer's copy.
            invoice.setVatRate(settings.isVatEnabled() ? settings.getVatRate() : BigDecimal.ZERO);
            invoice.setVatAmount(vatOn(gross, settings));

            if ("DRYRUN".equalsIgnoreCase(mode)) {
                regime.sign(invoice, settings.getTaxId(), deviceId);
                FiscalRegimes.markSigned(invoice, regime);
            } else if (regime.canFileLive()) {
                regime.sign(invoice, settings.getTaxId(), deviceId);
                FiscalRegimes.markSigned(invoice, regime);
            } else {
                // Left PENDING rather than marked SIGNED. A receipt that says it
                // was filed when it was not is the one failure mode an operator
                // cannot recover from at audit.
                log.warn("{} cannot file live yet — invoice {} left PENDING",
                        regime.code(), invoice.getId());
            }
        } catch (Exception e) {
            invoice.setStatus(TaxInvoice.Status.FAILED);
            log.warn("Fiscal signing failed for invoice {} under {}: {}",
                    invoice.getId(), regime.code(), e.getMessage());
        }
        return invoices.save(invoice);
    }

    /**
     * The VAT inside, or on top of, a gross amount.
     *
     * <p>Which of those it is depends on {@code pricesIncludeVat}, and getting it
     * backwards misstates every return by the whole VAT amount.
     */
    static BigDecimal vatOn(BigDecimal gross, TaxSettings settings) {
        if (!settings.isVatEnabled() || settings.getVatRate() == null
                || settings.getVatRate().signum() == 0 || gross == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = settings.getVatRate();
        if (settings.isPricesIncludeVat()) {
            // gross * r / (100 + r)
            return gross.multiply(rate)
                    .divide(new BigDecimal("100").add(rate), 2, RoundingMode.HALF_UP);
        }
        return gross.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
