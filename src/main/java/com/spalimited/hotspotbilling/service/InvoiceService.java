package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Invoice;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.TaxSettings;
import com.spalimited.hotspotbilling.repository.InvoiceRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Issues and settles monthly subscriber invoices. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final SubscriberRepository subscribers;
    private final TaxService taxService;
    private final LedgerService ledgerService;

    private String nextNumber() {
        int year = LocalDate.now().getYear();
        String prefix = taxService.settings().getInvoicePrefix() + "-" + year + "-";
        long count = invoices.countByNumberStartingWith(prefix) + 1;
        return prefix + String.format("%06d", count);
    }

    @Transactional
    public Invoice issue(Subscriber sub, int months) {
        LocalDate dueOn = LocalDate.ofInstant(sub.getPaidUntil(), ZoneId.systemDefault());
        java.math.BigDecimal charge = sub.getMonthlyFee().multiply(java.math.BigDecimal.valueOf(months));

        // Read before saving: afterwards the new invoice is part of the balance.
        java.math.BigDecimal balanceBefore = ledgerService.balance(sub.getId());
        java.math.BigDecimal creditBefore = balanceBefore.signum() < 0
                ? balanceBefore.negate() : java.math.BigDecimal.ZERO;

        // The split is worked out once, here, and stored on the invoice. A
        // later rate change must not silently rewrite what was already issued.
        TaxSettings tax = taxService.settings();
        TaxSettings.Split split = tax.split(charge);

        Invoice invoice = invoices.save(Invoice.builder()
                .number(nextNumber())
                .subscriber(sub)
                // Gross is what the customer pays. With VAT-inclusive pricing
                // this equals the agreed fee, so the total never drifts from
                // what they were quoted.
                .amount(split.gross())
                .netAmount(split.net())
                .vatAmount(split.vat())
                .vatRate(tax.isVatEnabled() ? tax.getVatRate() : null)
                .vatInclusive(tax.isVatEnabled() ? tax.isPricesIncludeVat() : null)
                .months(months)
                .issuedOn(LocalDate.now())
                .dueOn(dueOn)
                .build());
        log.info("Issued invoice {} for {} ({} month(s), net {} + VAT {})",
                invoice.getNumber(), sub.getPppoeUsername(), months, split.net(), split.vat());

        // A customer sitting in credit has already paid for this. Settling it
        // here is what "credit applies to the next invoice" means in practice;
        // the running balance would net off either way, but leaving the
        // invoice unpaid would put them in the arrears list wrongly and
        // trigger a reminder they do not deserve.
        if (creditBefore.signum() > 0 && creditBefore.compareTo(split.gross()) >= 0) {
            invoice.setStatus(Invoice.Status.PAID);
            invoice.setPaidAt(Instant.now());
            invoice.setPaymentReference("Settled from account credit");
            invoice = invoices.save(invoice);
            log.info("{} settled from {} of existing credit", invoice.getNumber(), creditBefore);
        }
        return invoice;
    }

    /** Marks the subscriber's oldest unpaid invoice settled, if there is one. */
    @Transactional
    public void settleOldestUnpaid(Long subscriberId, String reference) {
        invoices.findFirstBySubscriberIdAndStatusOrderByDueOnAsc(subscriberId, Invoice.Status.UNPAID)
                .ifPresent(invoice -> {
                    invoice.setStatus(Invoice.Status.PAID);
                    invoice.setPaidAt(Instant.now());
                    invoice.setPaymentReference(reference);
                    invoices.save(invoice);
                    log.info("Invoice {} marked paid ({})", invoice.getNumber(), reference);
                });
    }

    @Transactional(readOnly = true)
    public List<Invoice> recent() {
        return invoices.findTop200ByOrderByIssuedOnDesc();
    }

    @Transactional(readOnly = true)
    public List<Invoice> unpaid() {
        return invoices.findByStatusOrderByDueOnAsc(Invoice.Status.UNPAID);
    }

    @Transactional(readOnly = true)
    public List<Invoice> forSubscriber(Long subscriberId) {
        return invoices.findBySubscriberIdOrderByIssuedOnDesc(subscriberId);
    }

    @Transactional
    public Invoice cancel(Long id) {
        Invoice invoice = invoices.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown invoice: " + id));
        invoice.setStatus(Invoice.Status.CANCELLED);
        return invoices.save(invoice);
    }

    /**
     * Issues invoices 5 days before each active subscription lapses, once
     * per cycle (skipped if an unpaid invoice already covers it).
     */
    @Transactional
    public int issueDueInvoices() {
        int issued = 0;
        Instant cutoff = Instant.now().plus(5, ChronoUnit.DAYS);
        for (Subscriber sub : subscribers.findByStatus(Subscriber.Status.ACTIVE)) {
            if (sub.getPaidUntil().isAfter(cutoff)) {
                continue;
            }
            boolean alreadyOpen = invoices
                    .findFirstBySubscriberIdAndStatusOrderByDueOnAsc(sub.getId(), Invoice.Status.UNPAID)
                    .isPresent();
            if (alreadyOpen) {
                continue;
            }
            issue(sub, 1);
            issued++;
        }
        return issued;
    }
}
