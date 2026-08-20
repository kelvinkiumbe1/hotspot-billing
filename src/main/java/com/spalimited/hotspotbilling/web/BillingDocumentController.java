package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CreditNote;
import com.spalimited.hotspotbilling.domain.Invoice;
import com.spalimited.hotspotbilling.domain.ProformaInvoice;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.InvoiceRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.BillingDocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Quotes and credit notes. */
@RestController
@RequestMapping("/api/admin/billing-documents")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FINANCE')")
public class BillingDocumentController {

    private final BillingDocumentService documents;
    private final SubscriberRepository subscribers;
    private final InvoiceRepository invoices;

    // --- Quotes ---

    public record QuoteRequest(
            @NotNull Long subscriberId,
            @Min(1) @Max(60) int months,
            /* Null uses the customer's own monthly fee. A business quote is
               often a negotiated figure rather than the list price. */
            BigDecimal amount,
            @Size(max = 500) String description,
            @Min(1) @Max(365) Integer validDays) {
    }

    @PostMapping("/quotes")
    public Map<String, Object> quote(@Valid @RequestBody QuoteRequest request, Principal principal) {
        ProformaInvoice quote = documents.quote(request.subscriberId(), request.months(),
                request.amount(), request.description(), request.validDays(), who(principal));
        return Map.of("quote", renderQuote(quote),
                "message", "Quote " + quote.getNumber() + " issued. It is not a debt and nobody "
                        + "will be chased for it until you turn it into an invoice.");
    }

    @GetMapping("/quotes")
    public Map<String, Object> quotes() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProformaInvoice q : documents.recentQuotes()) {
            rows.add(renderQuote(q));
        }
        return Map.of("quotes", rows);
    }

    @PostMapping("/quotes/{id}/convert")
    public Map<String, Object> convert(@PathVariable Long id, Principal principal) {
        Invoice invoice = documents.convert(id, who(principal));
        return Map.of("invoiceNumber", invoice.getNumber(),
                "message", "Invoice " + invoice.getNumber() + " issued. It is now money owed and "
                        + "appears in the arrears list if it goes unpaid.");
    }

    @PostMapping("/quotes/{id}/cancel")
    public Map<String, Object> cancelQuote(@PathVariable Long id, Principal principal) {
        ProformaInvoice quote = documents.cancelQuote(id, who(principal));
        return Map.of("quote", renderQuote(quote), "message", "Quote cancelled.");
    }

    // --- Credit notes ---

    public record CreditRequest(
            @NotNull Long subscriberId,
            /* Null for a goodwill credit that answers to no single invoice. */
            Long invoiceId,
            @NotNull BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/credit-notes")
    public Map<String, Object> credit(@Valid @RequestBody CreditRequest request, Principal principal) {
        CreditNote note = documents.credit(request.subscriberId(), request.invoiceId(),
                request.amount(), request.reason(), who(principal));
        return Map.of("creditNote", renderCredit(note),
                "message", "Credit note " + note.getNumber() + " issued for "
                        + note.getAmount().toPlainString() + ".");
    }

    @GetMapping("/credit-notes")
    public Map<String, Object> creditNotes() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CreditNote c : documents.recentCreditNotes()) {
            rows.add(renderCredit(c));
        }
        return Map.of("creditNotes", rows);
    }

    /**
     * One customer's invoices, with how much of each can still be credited.
     *
     * <p>The remaining figure is worked out here rather than in the browser, so
     * the limit shown is the limit enforced.
     */
    @GetMapping("/creditable/{subscriberId}")
    public Map<String, Object> creditable(@PathVariable Long subscriberId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Invoice invoice : invoices.findBySubscriberIdOrderByIssuedOnDesc(subscriberId)) {
            BigDecimal remaining = documents.creditableOn(invoice.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", invoice.getId());
            row.put("number", invoice.getNumber());
            row.put("amount", invoice.getAmount());
            row.put("status", invoice.getStatus());
            row.put("issuedOn", invoice.getIssuedOn());
            row.put("creditable", remaining);
            rows.add(row);
        }
        return Map.of("invoices", rows);
    }

    // --- Rendering ---

    private Map<String, Object> renderQuote(ProformaInvoice q) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", q.getId());
        row.put("number", q.getNumber());
        row.put("subscriberId", q.getSubscriberId());
        subscribers.findById(q.getSubscriberId()).ifPresent(sub -> {
            row.put("customer", sub.getFullName());
            row.put("pppoeUsername", sub.getPppoeUsername());
        });
        row.put("amount", q.getAmount());
        row.put("netAmount", q.getNetAmount());
        row.put("vatAmount", q.getVatAmount());
        row.put("months", q.getMonths());
        row.put("description", q.getDescription());
        row.put("issuedOn", q.getIssuedOn());
        row.put("validUntil", q.getValidUntil());
        // The stored status, plus whether it can still be acted on. A quote whose
        // date has passed is stored as ISSUED and is not live, and the screen has
        // to show the second thing rather than the first.
        row.put("status", q.getStatus());
        row.put("live", q.isLive());
        row.put("expired", q.getStatus() == ProformaInvoice.Status.ISSUED
                && q.getValidUntil().isBefore(LocalDate.now()));
        if (q.getInvoiceId() != null) {
            row.put("invoiceNumber", invoices.findById(q.getInvoiceId())
                    .map(Invoice::getNumber).orElse(null));
        }
        row.put("createdBy", q.getCreatedBy());
        return row;
    }

    private Map<String, Object> renderCredit(CreditNote c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("number", c.getNumber());
        row.put("subscriberId", c.getSubscriberId());
        subscribers.findById(c.getSubscriberId())
                .map(Subscriber::getFullName)
                .ifPresent(name -> row.put("customer", name));
        row.put("amount", c.getAmount());
        row.put("netAmount", c.getNetAmount());
        row.put("vatAmount", c.getVatAmount());
        row.put("reason", c.getReason());
        row.put("issuedOn", c.getIssuedOn());
        if (c.getInvoiceId() != null) {
            row.put("invoiceNumber", invoices.findById(c.getInvoiceId())
                    .map(Invoice::getNumber).orElse(null));
        }
        row.put("createdBy", c.getCreatedBy());
        return row;
    }

    private static String who(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
