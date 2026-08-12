package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.service.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Monthly PPPoE subscribers (HTTP Basic, ADMIN role). */
@RestController
@RequestMapping("/api/admin/subscribers")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CUSTOMERS')")
public class SubscriberController {

    private final SubscriberRepository subscribers;
    private final SubscriptionPaymentRepository payments;
    private final SubscriptionService subscriptionService;

    @GetMapping
    public List<Subscriber> all() {
        return subscribers.findAllByOrderByCreatedAtAsc();
    }

    public record CreateRequest(
            @NotBlank String fullName,
            @Pattern(regexp = "254\\d{9}", message = "Phone must be in 2547XXXXXXXX format") String phoneNumber,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._@-]{3,40}",
                    message = "PPPoE username must be 3-40 letters, digits, dots, dashes, @ or underscores")
            String pppoeUsername,
            @NotBlank @Size(min = 6) String pppoePassword,
            String bandwidth,
            @NotNull @Min(1) BigDecimal monthlyFee,
            @Min(0) @Max(12) Integer initialMonths,
            String initialMethod) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Subscriber create(@Valid @RequestBody CreateRequest request, java.security.Principal principal) {
        return subscriptionService.create(
                request.fullName(),
                request.phoneNumber(),
                request.pppoeUsername(),
                request.pppoePassword(),
                request.bandwidth(),
                request.monthlyFee(),
                request.initialMonths() != null ? request.initialMonths() : 1,
                "MPESA".equalsIgnoreCase(request.initialMethod())
                        ? SubscriptionPayment.Method.MPESA : SubscriptionPayment.Method.CASH,
                principal.getName());
    }

    public record ExtendRequest(@Min(1) @Max(1000) int amount,
                                @jakarta.validation.constraints.NotBlank String unit) {
    }

    /** Goodwill extension without payment — hours, days or months. */
    @PostMapping("/{id}/extend")
    public Subscriber extend(@PathVariable Long id, @Valid @RequestBody ExtendRequest request) {
        return subscriptionService.extendManually(id, request.amount(), request.unit());
    }

    public record MonthsRequest(@Min(1) @Max(12) int months) {
    }

    /** Records a cash/off-system payment and extends the subscription. */
    @PostMapping("/{id}/payments")
    public SubscriptionPayment recordPayment(@PathVariable Long id, @Valid @RequestBody MonthsRequest request) {
        return subscriptionService.recordCashPayment(id, request.months());
    }

    /** Sends an M-Pesa STK prompt to the subscriber's phone. */
    @PostMapping("/{id}/stk")
    public Map<String, Object> stk(@PathVariable Long id, @Valid @RequestBody MonthsRequest request) {
        SubscriptionPayment payment = subscriptionService.initiateStk(id, request.months());
        return Map.of(
                "paymentId", payment.getId(),
                "amount", payment.getAmount(),
                "message", "STK prompt sent — the subscriber should enter their M-Pesa PIN");
    }

    @GetMapping("/{id}/payments")
    public List<SubscriptionPayment> paymentHistory(@PathVariable Long id) {
        return payments.findBySubscriberIdOrderByCreatedAtDesc(id);
    }

    @PatchMapping("/{id}/suspend")
    public Subscriber suspend(@PathVariable Long id) {
        return subscriptionService.suspend(id);
    }

    @PatchMapping("/{id}/activate")
    public Subscriber activate(@PathVariable Long id) {
        return subscriptionService.activate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        subscriptionService.delete(id);
    }

    /* ---------------------------------------------------------------- */
    /* Bulk import (migrating from a previous system)                    */
    /* ---------------------------------------------------------------- */

    public record ImportRow(String fullName, String phoneNumber, String pppoeUsername,
                            String pppoePassword, String bandwidth, BigDecimal monthlyFee,
                            String expiry) {
    }

    public record RowError(int row, String pppoeUsername, String message) {
    }

    public record ImportResult(int created, int failed, int generatedPasswords, List<RowError> errors) {
    }

    /**
     * Imports subscribers straight into the database — no M-Pesa prompt and no
     * payment record is created (these customers already paid on their old
     * system). An optional expiry preserves the time they have left. Each row
     * is independent: a bad row is reported and the rest still import.
     */
    @PostMapping("/import")
    public ImportResult importSubscribers(@RequestBody List<ImportRow> rows,
                                          java.security.Principal principal) {
        int created = 0;
        int generated = 0;
        List<RowError> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ImportRow r = rows.get(i);
            try {
                if (isBlank(r.fullName())) throw new IllegalArgumentException("Name is required");
                String user = r.pppoeUsername() == null ? "" : r.pppoeUsername().trim();
                if (user.isBlank()) throw new IllegalArgumentException("PPPoE username is required");
                if (!user.matches("[a-zA-Z0-9._@-]{3,40}")) {
                    throw new IllegalArgumentException("Invalid PPPoE username '" + user + "'");
                }
                if (subscribers.findByPppoeUsername(user).isPresent()) {
                    throw new IllegalArgumentException("PPPoE username already exists: " + user);
                }
                String pass = r.pppoePassword();
                boolean gen = isBlank(pass);
                if (gen) pass = randomPassword();

                subscribers.save(Subscriber.builder()
                        .fullName(r.fullName().trim())
                        .phoneNumber(r.phoneNumber() == null ? null : r.phoneNumber().replaceAll("\\D", ""))
                        .pppoeUsername(user)
                        .pppoePassword(pass)
                        .bandwidth(normalizeBandwidth(r.bandwidth()))
                        .monthlyFee(r.monthlyFee() != null ? r.monthlyFee() : BigDecimal.ZERO)
                        .status(Subscriber.Status.ACTIVE)
                        .paidUntil(parseExpiry(r.expiry()))
                        .createdBy(principal.getName())
                        .build());
                created++;
                if (gen) generated++;
            } catch (Exception e) {
                errors.add(new RowError(i + 1, r.pppoeUsername(), rootMessage(e)));
            }
        }
        return new ImportResult(created, errors.size(), generated, errors);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** "5" -> "5M/5M"; "5M/5M" kept as-is; blank -> null. */
    private static String normalizeBandwidth(String bw) {
        if (isBlank(bw)) return null;
        bw = bw.trim();
        if (bw.matches("\\d+")) return bw + "M/" + bw + "M";
        return bw;
    }

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,          // 2026-08-31
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
    };

    /** Parses an optional expiry date to end-of-day UTC; blank -> null. */
    private static Instant parseExpiry(String s) {
        if (isBlank(s)) return null;
        String v = s.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDate d = LocalDate.parse(v, f);
                return d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("Unrecognised expiry date '" + s + "' (use YYYY-MM-DD)");
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final String PW_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(PW_CHARS.charAt(RNG.nextInt(PW_CHARS.length())));
        return sb.toString();
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m != null ? m : c.getClass().getSimpleName();
    }
}
