package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.service.BranchScope;
import com.spalimited.hotspotbilling.service.FupService;
import com.spalimited.hotspotbilling.service.SubscriberUsageService;
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
import java.util.LinkedHashMap;
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
    private final SubscriberUsageService subscriberUsage;
    private final FupService fupService;
    private final BranchScope branchScope;

    /**
     * Every customer, or every customer of the caller's branch.
     *
     * <p>Head office gets the lot. A branch login gets its own, and a customer
     * with no branch set stays with head office rather than appearing to
     * everybody -- see BranchScope.
     */
    @GetMapping
    public List<Subscriber> all() {
        return branchScope.filter(subscribers.findAllByOrderByCreatedAtAsc());
    }

    /**
     * One customer, by id, with the branch check.
     *
     * <p>Every by-id path below goes through this rather than findById. Filtering
     * only the list would leave a branch login able to walk ids one at a time,
     * which is the same leak with more steps.
     */
    private Subscriber reachable(Long id) {
        Subscriber sub = subscribers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such subscriber"));
        branchScope.require(sub);
        return sub;
    }

    public record CreateRequest(
            @NotBlank String fullName,
            @com.spalimited.hotspotbilling.config.Phone String phoneNumber,
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
        Subscriber created = subscriptionService.create(
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
        // A branch login files its new customer into its own branch. Without
        // this the customer lands at head office and the person who just created
        // them cannot see them, which reads as the save having failed.
        Long branch = branchScope.current();
        if (branch != null) {
            created.setBranchId(branch);
            created = subscribers.save(created);
        }
        return created;
    }

    public record ExtendRequest(@Min(1) @Max(1000) int amount,
                                @jakarta.validation.constraints.NotBlank String unit) {
    }

    /** Goodwill extension without payment — hours, days or months. */
    @PostMapping("/{id}/extend")
    public Subscriber extend(@PathVariable Long id, @Valid @RequestBody ExtendRequest request) {
        reachable(id);
        return subscriptionService.extendManually(id, request.amount(), request.unit());
    }

    public record MonthsRequest(@Min(1) @Max(12) int months) {
    }

    /** Records a cash/off-system payment and extends the subscription. */
    @PostMapping("/{id}/payments")
    public SubscriptionPayment recordPayment(@PathVariable Long id, @Valid @RequestBody MonthsRequest request) {
        reachable(id);
        return subscriptionService.recordCashPayment(id, request.months());
    }

    /** Sends an M-Pesa STK prompt to the subscriber's phone. */
    @PostMapping("/{id}/stk")
    public Map<String, Object> stk(@PathVariable Long id, @Valid @RequestBody MonthsRequest request) {
        reachable(id);
        SubscriptionPayment payment = subscriptionService.initiateStk(id, request.months());
        return Map.of(
                "paymentId", payment.getId(),
                "amount", payment.getAmount(),
                "message", "STK prompt sent — the subscriber should enter their M-Pesa PIN");
    }

    @GetMapping("/{id}/payments")
    public List<SubscriptionPayment> paymentHistory(@PathVariable Long id) {
        reachable(id);
        return payments.findBySubscriberIdOrderByCreatedAtDesc(id);
    }

    /**
     * Everything an operator can change about a customer.
     *
     * <p>Every field is optional and null means "leave it alone", so a screen
     * that only edits the name sends only the name. Bandwidth and the static
     * address accept a blank string as a real value, because taking a speed limit
     * or a fixed address off again has to be possible.
     */
    public record UpdateRequest(
            String fullName,
            String phoneNumber,
            @Pattern(regexp = "|[a-zA-Z0-9._@-]{3,40}",
                    message = "PPPoE username must be 3-40 letters, digits, dots, dashes, @ or underscores")
            String pppoeUsername,
            @Size(min = 6, message = "A PPPoE password needs at least 6 characters")
            String pppoePassword,
            String bandwidth,
            @Min(1) BigDecimal monthlyFee,
            Long routerId,
            Long branchId,
            String staticIp) {
    }

    /**
     * Edits a customer.
     *
     * <p>The reply carries the server's own sentence about what happened rather
     * than a bare 200, because two of these changes interrupt the customer -- a
     * renamed username leaves them offline until their own router is updated, and
     * a speed change only lands when the line reconnects -- and whoever pressed
     * Save is the person who has to tell them.
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateRequest request,
                                      java.security.Principal principal) {
        reachable(id);
        SubscriptionService.Edited edited = subscriptionService.update(
                id,
                request.fullName(),
                request.phoneNumber(),
                request.pppoeUsername(),
                request.pppoePassword(),
                request.bandwidth(),
                request.monthlyFee(),
                request.routerId(),
                request.branchId(),
                request.staticIp(),
                principal == null ? "admin" : principal.getName());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscriber", edited.subscriber());
        out.put("changed", edited.changes());
        out.put("reconnectNeeded", edited.reconnectNeeded());
        out.put("message", edited.note());
        return out;
    }

    @PatchMapping("/{id}/suspend")
    public Subscriber suspend(@PathVariable Long id) {
        reachable(id);
        return subscriptionService.suspend(id);
    }

    @PatchMapping("/{id}/activate")
    public Subscriber activate(@PathVariable Long id) {
        reachable(id);
        return subscriptionService.activate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        reachable(id);
        subscriptionService.delete(id);
    }

    /* ---------------------------------------------------------------- */
    /* Usage and fair use                                                */
    /* ---------------------------------------------------------------- */

    /**
     * One customer's usage: a day-by-day series, this cycle's total, and where
     * they stand against their cap if they have one.
     */
    @GetMapping("/{id}/usage")
    public Map<String, Object> usage(@PathVariable Long id,
                                     @RequestParam(defaultValue = "30") @Min(1) @Max(400) int days) {
        Subscriber sub = reachable(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscriberId", id);
        out.put("days", days);
        out.put("series", subscriberUsage.dailySeries(id, days));
        out.put("thisCycleMb", subscriberUsage.thisCycleBytes(id) / (1024L * 1024L));
        out.put("cycleStart", subscriberUsage.cycleStart(subscriberUsage.today()).toString());
        // Null when the customer is uncapped, which the screen reads as "no cap"
        // rather than as a cap of zero.
        out.put("cap", subscriberUsage.capStatus(sub));
        return out;
    }

    public record FairUseRequest(
            @Min(1) @Max(10_000_000) Integer dataCapMb,
            String action,
            @Pattern(regexp = "|[0-9]+[kKmMgG]?/[0-9]+[kKmMgG]?",
                    message = "Rate must look like 2M/2M")
            String fupRate) {
    }

    /**
     * Sets or clears a customer's allowance.
     *
     * <p>A null cap clears it. Clearing does not itself put a throttled customer
     * back to full speed -- the sweep does that within ten minutes, and doing it
     * here as well would mean two code paths racing to talk to the same router.
     */
    @PatchMapping("/{id}/fair-use")
    public Map<String, Object> fairUse(@PathVariable Long id, @Valid @RequestBody FairUseRequest request) {
        Subscriber sub = reachable(id);
        sub.setDataCapMb(request.dataCapMb());
        sub.setFupAction(request.action() == null || request.action().isBlank()
                ? null : Plan.FupAction.valueOf(request.action()));
        sub.setFupRate(request.fupRate() == null || request.fupRate().isBlank()
                ? null : request.fupRate().trim());
        subscribers.save(sub);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cap", subscriberUsage.capStatus(sub));
        out.put("message", request.dataCapMb() == null
                ? "Allowance removed. Any throttle in place is lifted within ten minutes."
                : "Allowance set to " + request.dataCapMb() + "MB a month.");
        return out;
    }

    /** Lifts a throttle or block right now, without waiting for the sweep. */
    @PostMapping("/{id}/fair-use/lift")
    public Map<String, Object> liftFairUse(@PathVariable Long id) {
        Subscriber sub = reachable(id);
        if (sub.getFupAppliedAt() == null) {
            return Map.of("ok", true, "message", "Nothing was applied to this customer.");
        }
        // Clearing the cycle is what makes the sweep treat it as stale and
        // restore, so this reuses the one restore path rather than adding a
        // second one that could drift from it.
        sub.setFupCycle(null);
        subscribers.save(sub);
        boolean done = fupService.reviewSubscriber(sub);
        return Map.of("ok", done, "message", done
                ? "Full speed restored. They reconnect within a few seconds."
                : "Could not reach the router. It will retry within ten minutes.");
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
                        // Same reason as create(): a branch that imports into
                        // head office cannot see what it just imported.
                        .branchId(branchScope.current())
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
