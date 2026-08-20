package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.TaxSettings;
import com.spalimited.hotspotbilling.repository.TaxSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Reads and updates the single VAT settings row, creating it on first use. */
@Service
@RequiredArgsConstructor
public class TaxService {

    private static final long ROW_ID = 1L;

    private final TaxSettingsRepository repository;

    @Transactional
    public TaxSettings settings() {
        return repository.findById(ROW_ID)
                .orElseGet(() -> repository.save(TaxSettings.builder().id(ROW_ID).build()));
    }

    @Transactional
    public TaxSettings save(boolean vatEnabled, BigDecimal vatRate, boolean pricesIncludeVat,
                            String taxId, String legalName, String addressLine, String invoicePrefix,
                             String regime) {
        if (vatRate == null || vatRate.signum() < 0 || vatRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("The VAT rate must be between 0 and 100");
        }
        com.spalimited.hotspotbilling.service.tax.FiscalRegime chosen =
                com.spalimited.hotspotbilling.service.tax.FiscalRegimes.byCode(regime);
        if (regime != null && !regime.isBlank()
                && !com.spalimited.hotspotbilling.service.tax.FiscalRegimes.known(regime)) {
            throw new IllegalArgumentException("That is not a tax authority Zidi can file with");
        }
        if (vatEnabled && (taxId == null || taxId.isBlank())) {
            // Named for wherever they actually are. Asking a Lagos operator for
            // their "KRA PIN" tells them the product was not built for them.
            throw new IllegalArgumentException("A tax invoice has to show your "
                    + chosen.taxIdLabel() + " — add it before switching VAT on");
        }
        TaxSettings settings = settings();
        settings.setVatEnabled(vatEnabled);
        settings.setVatRate(vatRate);
        settings.setPricesIncludeVat(pricesIncludeVat);
        settings.setTaxId(blankToNull(taxId));
        settings.setLegalName(blankToNull(legalName));
        settings.setAddressLine(blankToNull(addressLine));
        settings.setInvoicePrefix(invoicePrefix == null || invoicePrefix.isBlank()
                ? "INV" : invoicePrefix.trim().toUpperCase());
        settings.setRegime(chosen.code());
        return repository.save(settings);
    }

    /** Splits an amount into net and VAT under the current settings. */
    public TaxSettings.Split split(BigDecimal amount) {
        return settings().split(amount);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
