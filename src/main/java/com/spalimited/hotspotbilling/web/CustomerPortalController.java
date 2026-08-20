package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Invoice;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.service.InvoiceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer self-service portal for monthly subscribers. They sign in with
 * the PPPoE username and password already configured in their router, and
 * see their status, usage, invoices and payment history.
 */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class CustomerPortalController {

    private final SubscriberRepository subscribers;
    private final SubscriptionPaymentRepository payments;
    private final InvoiceService invoiceService;
    private final com.spalimited.hotspotbilling.service.SubscriberUsageService subscriberUsage;
    private final com.spalimited.hotspotbilling.service.PhoneVerificationService phoneVerification;

    public record LoginRequest(@NotBlank String pppoeUsername, @NotBlank String pppoePassword) {
    }

    /** Verifies the router credentials and returns the full account view. */
    @PostMapping("/account")
    public Map<String, Object> account(@Valid @RequestBody LoginRequest request) {
        Subscriber sub = subscribers.findByPppoeUsername(request.pppoeUsername())
                .filter(s -> s.getPppoePassword().equals(request.pppoePassword()))
                .orElseThrow(() -> new IllegalArgumentException("Wrong username or password"));

        List<SubscriptionPayment> history = payments.findBySubscriberIdOrderByCreatedAtDesc(sub.getId());
        List<Invoice> invoices = invoiceService.forSubscriber(sub.getId());

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", sub.getId());
        account.put("fullName", sub.getFullName());
        account.put("phoneNumber", sub.getPhoneNumber());
        account.put("pppoeUsername", sub.getPppoeUsername());
        account.put("bandwidth", sub.getBandwidth());
        account.put("monthlyFee", sub.getMonthlyFee());
        account.put("status", sub.getStatus());
        account.put("paidUntil", sub.getPaidUntil());
        account.put("dataUsedMb", sub.getDataUsedMbOrZero());
        account.put("lastSeenOnlineAt", sub.getLastSeenOnlineAt());
        // So the page can ask them to confirm it. A number nobody proved is
        // how a renewal reminder, a receipt and a voucher all reach a stranger.
        account.put("phoneVerified", phoneVerification.isVerified(sub.getPhoneNumber()));
        account.put("lastPaymentMethod", sub.getLastPaymentMethod());
        account.put("lastPaymentAt", sub.getLastPaymentAt());

        Map<String, Object> out = new LinkedHashMap<>();
        // The question this portal exists to answer without a phone call.
        // Thirty days rather than the cap period, so the graph is the same
        // length every day of the month instead of one bar on the 1st.
        out.put("usage", Map.of(
                "thisCycleMb", subscriberUsage.thisCycleBytes(sub.getId()) / (1024L * 1024L),
                "cycleStart", subscriberUsage.cycleStart(subscriberUsage.today()).toString(),
                "daily", subscriberUsage.dailySeries(sub.getId(), 30)));
        Map<String, Object> cap = subscriberUsage.capStatus(sub);
        if (cap != null) {
            out.put("cap", cap);
        }
        out.put("account", account);
        out.put("payments", history.stream().map(p -> Map.of(
                "amount", p.getAmount(),
                "months", p.getMonths(),
                "method", p.getMethod(),
                "status", p.getStatus(),
                "receipt", p.getMpesaReceiptNumber() != null ? p.getMpesaReceiptNumber() : "",
                "date", p.getCreatedAt())).toList());
        out.put("invoices", invoices.stream().map(i -> Map.of(
                "number", i.getNumber(),
                "amount", i.getAmount(),
                "months", i.getMonths(),
                "status", i.getStatus(),
                "issuedOn", i.getIssuedOn().toString(),
                "dueOn", i.getDueOn().toString())).toList());
        return out;
    }
}
