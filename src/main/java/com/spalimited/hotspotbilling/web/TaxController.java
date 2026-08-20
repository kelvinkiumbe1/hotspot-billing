package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Invoice;
import com.spalimited.hotspotbilling.domain.TaxSettings;
import com.spalimited.hotspotbilling.repository.InvoiceRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.TaxService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** VAT configuration, and the figures that make up a tax invoice. */
@RestController
@RequestMapping("/api/admin/tax")
@RequiredArgsConstructor
public class TaxController {

    private final TaxService taxService;
    private final InvoiceRepository invoices;
    private final PortalSettingsService portalSettingsService;
    private final AuditService audit;

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('FINANCE')")
    public Map<String, Object> settings() {
        return describe(taxService.settings());
    }

    private Map<String, Object> describe(TaxSettings t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vatEnabled", t.isVatEnabled());
        out.put("vatRate", t.getVatRate());
        out.put("pricesIncludeVat", t.isPricesIncludeVat());
        out.put("taxId", t.getTaxId());
        out.put("regime", t.getRegime());
        // The label, not just the code: the form has to ask a Lagos operator for
        // a TIN and a Nairobi one for a KRA PIN, from the same screen.
        out.put("taxIdLabel", com.spalimited.hotspotbilling.service.tax.FiscalRegimes
                .byCode(t.getRegime()).taxIdLabel());
        out.put("regimes", com.spalimited.hotspotbilling.service.tax.FiscalRegimes.all().stream()
                .map(r -> Map.of("code", r.code(), "label", r.label(),
                        "taxIdLabel", r.taxIdLabel(),
                        "defaultVatRate", r.defaultVatRate(),
                        "canFileLive", r.canFileLive()))
                .toList());
        out.put("legalName", t.getLegalName());
        out.put("addressLine", t.getAddressLine());
        out.put("invoicePrefix", t.getInvoicePrefix());
        out.put("updatedAt", t.getUpdatedAt());
        // A worked example, so the effect of the inclusive flag is obvious
        // before anyone saves it.
        TaxSettings.Split example = t.split(new BigDecimal("3500"));
        out.put("example", Map.of(
                "charge", new BigDecimal("3500.00"),
                "net", example.net(),
                "vat", example.vat(),
                "gross", example.gross()));
        return out;
    }

    public record TaxRequest(
            boolean vatEnabled,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal vatRate,
            boolean pricesIncludeVat,
            String taxId,
            String legalName,
            String addressLine,
            String invoicePrefix,
            String regime) {
    }

    /** Changing tax treatment is an owner-level decision, not day-to-day finance. */
    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> save(@Valid @RequestBody TaxRequest request, Principal principal) {
        TaxSettings saved = taxService.save(request.vatEnabled(), request.vatRate(),
                request.pricesIncludeVat(), request.taxId(), request.legalName(),
                request.addressLine(), request.invoicePrefix(), request.regime());
        audit.record(principal, "tax.settings", "VAT " + (saved.isVatEnabled()
                ? "on at " + saved.getVatRate() + "% ("
                        + (saved.isPricesIncludeVat() ? "prices include VAT" : "VAT added on top") + ")"
                : "switched off"));
        return describe(saved);
    }

    /**
     * Everything needed to render one tax invoice. Figures come from the
     * invoice row rather than being recalculated, so a rate change since it
     * was issued cannot alter a document already sent to a customer.
     */
    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('FINANCE')")
    public Map<String, Object> invoice(@PathVariable Long id) {
        Invoice invoice = invoices.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown invoice: " + id));
        TaxSettings tax = taxService.settings();

        boolean taxed = invoice.getVatAmount() != null && invoice.getVatAmount().signum() > 0;
        BigDecimal gross = invoice.getAmount();
        // Invoices raised before VAT existed have no split stored; showing the
        // whole amount as net is honest, and taxed stays false so the document
        // is not titled a tax invoice.
        BigDecimal net = invoice.getNetAmount() != null ? invoice.getNetAmount() : gross;
        BigDecimal vat = invoice.getVatAmount() != null ? invoice.getVatAmount() : BigDecimal.ZERO;

        Map<String, Object> seller = new LinkedHashMap<>();
        seller.put("name", tax.getLegalName() != null
                ? tax.getLegalName() : portalSettingsService.settings().getBusinessName());
        seller.put("taxId", tax.getTaxId());
        seller.put("taxIdLabel", com.spalimited.hotspotbilling.service.tax.FiscalRegimes
                .byCode(tax.getRegime()).taxIdLabel());
        seller.put("address", tax.getAddressLine());

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", invoice.getSubscriber().getFullName());
        customer.put("phone", invoice.getSubscriber().getPhoneNumber());
        customer.put("account", invoice.getSubscriber().getPppoeUsername());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", invoice.getNumber());
        out.put("title", taxed ? "TAX INVOICE" : "INVOICE");
        out.put("taxed", taxed);
        out.put("issuedOn", invoice.getIssuedOn());
        out.put("dueOn", invoice.getDueOn());
        out.put("status", invoice.getStatus());
        out.put("paidAt", invoice.getPaidAt());
        out.put("paymentReference", invoice.getPaymentReference());
        out.put("seller", seller);
        out.put("customer", customer);
        out.put("description", "Internet access — " + invoice.getMonths()
                + (invoice.getMonths() == 1 ? " month" : " months"));
        out.put("months", invoice.getMonths());
        out.put("net", net);
        out.put("vat", vat);
        out.put("gross", gross);
        out.put("vatRate", invoice.getVatRate());
        out.put("vatInclusive", invoice.getVatInclusive());
        return out;
    }
}
