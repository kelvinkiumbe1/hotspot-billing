package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.TaxInvoice;
import com.spalimited.hotspotbilling.repository.TaxInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * KRA tax invoices produced by eTIMS fiscalisation, newest first. Read-only —
 * invoices are created automatically on each sale by {@code EtimsService}.
 */
@RestController
@RequestMapping("/api/admin/tax-invoices")
@RequiredArgsConstructor
public class TaxInvoiceController {

    private final TaxInvoiceRepository invoices;

    @GetMapping
    @PreAuthorize("hasAuthority('FINANCE')")
    public List<TaxInvoice> list() {
        return invoices.findTop200ByOrderByCreatedAtDesc();
    }
}
