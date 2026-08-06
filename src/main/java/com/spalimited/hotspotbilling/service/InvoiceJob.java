package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Issues upcoming monthly invoices once a day. */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceJob {

    private final InvoiceService invoiceService;
    private final AuditService audit;

    @Scheduled(cron = "0 30 6 * * *")
    public void run() {
        try {
            int issued = invoiceService.issueDueInvoices();
            if (issued > 0) {
                audit.system("invoice.batch", "Issued " + issued + " monthly invoice(s)");
            }
        } catch (Exception e) {
            log.warn("Invoice run failed: {}", e.getMessage());
        }
    }
}
