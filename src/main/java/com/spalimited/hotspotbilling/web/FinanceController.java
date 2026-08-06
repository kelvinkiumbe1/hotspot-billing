package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.InvoiceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Money side of the business: invoices, expenses, and reports
 * (HTTP Basic, ADMIN role).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FINANCE')")
public class FinanceController {

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoices;
    private final ExpenseRepository expenses;
    private final PaymentRepository payments;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final SubscriberRepository subscribers;
    private final AuditEventRepository auditEvents;
    private final AuditService audit;

    // --- Invoices ---

    @GetMapping("/invoices")
    public List<Invoice> allInvoices(@RequestParam(required = false) String status) {
        return "unpaid".equalsIgnoreCase(status) ? invoiceService.unpaid() : invoiceService.recent();
    }

    public record IssueInvoiceRequest(@NotNull Long subscriberId, @Min(1) int months) {
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public Invoice issueInvoice(@Valid @RequestBody IssueInvoiceRequest request, Principal principal) {
        Subscriber sub = subscribers.findById(request.subscriberId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown subscriber: " + request.subscriberId()));
        Invoice invoice = invoiceService.issue(sub, request.months());
        audit.record(principal, "invoice.issue", "Issued " + invoice.getNumber() + " for " + sub.getPppoeUsername());
        return invoice;
    }

    @PostMapping("/invoices/run")
    public Map<String, Object> runInvoicing(Principal principal) {
        int issued = invoiceService.issueDueInvoices();
        audit.record(principal, "invoice.batch", "Ran invoicing manually — " + issued + " issued");
        return Map.of("issued", issued, "message", issued + " invoice(s) issued");
    }

    @PatchMapping("/invoices/{id}/cancel")
    public Invoice cancelInvoice(@PathVariable Long id, Principal principal) {
        Invoice invoice = invoiceService.cancel(id);
        audit.record(principal, "invoice.cancel", "Cancelled " + invoice.getNumber());
        return invoice;
    }

    @GetMapping("/subscribers/{id}/invoices")
    public List<Invoice> subscriberInvoices(@PathVariable Long id) {
        return invoiceService.forSubscriber(id);
    }

    // --- Expenses ---

    @GetMapping("/expenses")
    public List<Expense> allExpenses() {
        return expenses.findTop200ByOrderByIncurredOnDesc();
    }

    public record ExpenseRequest(
            @NotBlank String description,
            @NotNull Expense.Category category,
            @NotNull BigDecimal amount,
            LocalDate incurredOn) {
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public Expense addExpense(@Valid @RequestBody ExpenseRequest request, Principal principal) {
        Expense expense = expenses.save(Expense.builder()
                .description(request.description())
                .category(request.category())
                .amount(request.amount())
                .incurredOn(request.incurredOn() != null ? request.incurredOn() : LocalDate.now())
                .recordedBy(principal.getName())
                .build());
        audit.record(principal, "expense.add", "Recorded expense KES " + expense.getAmount()
                + " (" + expense.getCategory() + ")");
        return expense;
    }

    @DeleteMapping("/expenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExpense(@PathVariable Long id, Principal principal) {
        expenses.findById(id).ifPresent(e -> {
            audit.record(principal, "expense.delete", "Deleted expense KES " + e.getAmount());
            expenses.delete(e);
        });
    }

    // --- Reports ---

    /**
     * Money in and out for the last N days, plus expected recurring
     * revenue and receivables.
     */
    @GetMapping("/reports/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        LocalDate fromDate = LocalDate.now().minusDays(days);

        BigDecimal voucherRevenue = payments.findAll().stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCESS && p.getCreatedAt().isAfter(from))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal subscriptionRevenue = subscriptionPayments.findAll().stream()
                .filter(p -> p.getStatus() == SubscriptionPayment.Status.SUCCESS && p.getCreatedAt().isAfter(from))
                .map(SubscriptionPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Expense> periodExpenses = expenses.findByIncurredOnBetweenOrderByIncurredOnDesc(fromDate, LocalDate.now());
        BigDecimal totalExpenses = periodExpenses.stream()
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (Expense e : periodExpenses) {
            byCategory.merge(e.getCategory().name(), e.getAmount(), BigDecimal::add);
        }

        List<Subscriber> active = subscribers.findByStatus(Subscriber.Status.ACTIVE);
        BigDecimal mrr = active.stream()
                .map(Subscriber::getMonthlyFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal receivables = invoiceService.unpaid().stream()
                .map(Invoice::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal revenue = voucherRevenue.add(subscriptionRevenue);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("voucherRevenue", voucherRevenue);
        out.put("subscriptionRevenue", subscriptionRevenue);
        out.put("totalRevenue", revenue);
        out.put("totalExpenses", totalExpenses);
        out.put("profit", revenue.subtract(totalExpenses));
        out.put("expensesByCategory", byCategory);
        out.put("expectedMonthlyRevenue", mrr);
        out.put("openReceivables", receivables);
        out.put("activeSubscribers", active.size());
        return out;
    }

    /** Daily revenue series for charts. */
    @GetMapping("/reports/daily")
    public List<Map<String, Object>> daily(@RequestParam(defaultValue = "30") int days) {
        Map<LocalDate, BigDecimal> series = new TreeMap<>();
        for (int i = days - 1; i >= 0; i--) {
            series.put(LocalDate.now().minusDays(i), BigDecimal.ZERO);
        }
        ZoneId zone = ZoneId.systemDefault();
        payments.findAll().stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCESS)
                .forEach(p -> {
                    LocalDate d = LocalDate.ofInstant(p.getCreatedAt(), zone);
                    if (series.containsKey(d)) {
                        series.merge(d, p.getAmount(), BigDecimal::add);
                    }
                });
        subscriptionPayments.findAll().stream()
                .filter(p -> p.getStatus() == SubscriptionPayment.Status.SUCCESS)
                .forEach(p -> {
                    LocalDate d = LocalDate.ofInstant(p.getCreatedAt(), zone);
                    if (series.containsKey(d)) {
                        series.merge(d, p.getAmount(), BigDecimal::add);
                    }
                });
        List<Map<String, Object>> out = new ArrayList<>();
        series.forEach((date, amount) -> out.add(Map.of("date", date.toString(), "amount", amount)));
        return out;
    }

    // --- Audit log ---

    @GetMapping("/audit")
    public List<AuditEvent> auditLog() {
        return auditEvents.findTop200ByOrderByCreatedAtDesc();
    }
}
