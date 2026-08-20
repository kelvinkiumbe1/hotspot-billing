package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.service.PlatformBillingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this ISP owes Zidi for the platform this month. Usage-based rather than a
 * flat subscription: a small cut of hotspot takings plus a flat fee per fixed-
 * line subscriber, and nothing at all for a quiet month. Read straight from the
 * ISP's own payments and subscribers, so it always matches what they actually
 * earned.
 */
@RestController
@RequestMapping("/api/admin/platform-billing")
@RequiredArgsConstructor
// What the ISP owes Zidi, and paying it. Finance rather than any signed-in
// desk: the amount is worked out here rather than sent by the caller, so this
// was never a way to move money somewhere else -- but a support account had no
// business pushing a payment prompt to a number of its choosing either.
@PreAuthorize("hasAuthority('FINANCE')")
public class PlatformBillingController {

    private final PaymentRepository payments;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final SubscriberRepository subscribers;
    private final StaffUserRepository staff;
    private final PlatformBillingClient platformClient;

    /** 2.5% of hotspot revenue. */
    private static final BigDecimal HOTSPOT_RATE = new BigDecimal("0.025");
    /** KES 25 per active PPPoE (fixed-line) subscriber. */
    private static final BigDecimal PPPOE_PER_USER = new BigDecimal("25");
    /** Earn less than this in a month and the platform is free. */
    private static final BigDecimal FREE_THRESHOLD = new BigDecimal("3000");
    /** Free for this long after the account is created, whatever they earn. */
    private static final int TRIAL_DAYS = 14;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> bill() {
        LocalDate today = LocalDate.now(ZONE);
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant();

        BigDecimal hotspotRevenue = payments.findAll().stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCESS)
                .filter(p -> !paidAt(p.getCompletedAt(), p.getCreatedAt()).isBefore(monthStart))
                .map(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pppoeRevenue = subscriptionPayments.findAll().stream()
                .filter(p -> p.getStatus() == SubscriptionPayment.Status.SUCCESS)
                .filter(p -> !paidAt(p.getCompletedAt(), p.getCreatedAt()).isBefore(monthStart))
                .map(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long activePppoe = subscribers.findAll().stream()
                .filter(s -> s.getStatus() == Subscriber.Status.ACTIVE)
                .count();

        // Account creation = the earliest staff login (the owner seeded at
        // provisioning). The first 14 days from then are free whatever they earn.
        Instant accountCreated = staff.findAll().stream()
                .map(StaffUser::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(Instant.now());
        Instant trialEnds = accountCreated.plus(Duration.ofDays(TRIAL_DAYS));
        boolean inTrial = Instant.now().isBefore(trialEnds);
        long trialDaysLeft = inTrial
                ? Math.max(0, Duration.between(Instant.now(), trialEnds).toDays() + 1) : 0;

        BigDecimal totalEarnings = hotspotRevenue.add(pppoeRevenue);
        boolean lowEarnings = totalEarnings.compareTo(FREE_THRESHOLD) < 0;
        boolean free = inTrial || lowEarnings;
        String freeReason = inTrial ? "TRIAL" : (lowEarnings ? "LOW_EARNINGS" : null);

        BigDecimal hotspotFee = free ? BigDecimal.ZERO
                : hotspotRevenue.multiply(HOTSPOT_RATE).setScale(0, RoundingMode.HALF_UP);
        BigDecimal pppoeFee = free ? BigDecimal.ZERO
                : PPPOE_PER_USER.multiply(BigDecimal.valueOf(activePppoe));
        BigDecimal amountDue = hotspotFee.add(pppoeFee);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", YearMonth.from(today).toString());
        out.put("daysLeftInMonth", today.lengthOfMonth() - today.getDayOfMonth());
        out.put("hotspotRevenue", hotspotRevenue);
        out.put("pppoeRevenue", pppoeRevenue);
        out.put("totalEarnings", totalEarnings);
        out.put("activePppoeUsers", activePppoe);
        out.put("free", free);
        out.put("freeReason", freeReason);
        out.put("inTrial", inTrial);
        out.put("trialDaysLeft", trialDaysLeft);
        out.put("trialDays", TRIAL_DAYS);
        out.put("freeThreshold", FREE_THRESHOLD);
        out.put("hotspotRatePercent", new BigDecimal("2.5"));
        out.put("pppoePerUser", PPPOE_PER_USER);
        out.put("hotspotFee", hotspotFee);
        out.put("pppoeFee", pppoeFee);
        out.put("amountDue", amountDue);
        return out;
    }

    public record PayRequest(String phone) {
    }

    /**
     * Collect this month's platform fee by M-Pesa. The tenant computes the
     * amount (from its own revenue) and hands it, with the owner's M-Pesa
     * number, to the control plane which owns Zidi's gateway and the invoice.
     */
    @PostMapping("/pay")
    public Map<String, Object> pay(@RequestBody PayRequest request) {
        Map<String, Object> b = bill();
        if (Boolean.TRUE.equals(b.get("free"))) {
            return Map.of("status", "NOTHING_DUE", "message", "Nothing to pay this month.");
        }
        BigDecimal amount = (BigDecimal) b.get("amountDue");
        if (amount == null || amount.signum() <= 0) {
            return Map.of("status", "NOTHING_DUE", "message", "Nothing to pay this month.");
        }
        if (!platformClient.configured()) {
            return Map.of("status", "UNCONFIGURED",
                    "message", "Platform billing isn't set up on this server yet.");
        }
        String phone = request.phone() == null ? "" : request.phone().replaceAll("\\D", "");
        if (!phone.matches("254\\d{9}")) {
            throw new IllegalArgumentException("Enter a valid M-Pesa number in 2547XXXXXXXX format.");
        }
        try {
            return platformClient.charge((String) b.get("month"), amount, phone);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    /** The state of this month's platform invoice (paid / pending / none). */
    @GetMapping("/payment-status")
    public Map<String, Object> paymentStatus() {
        if (!platformClient.configured()) {
            return Map.of("status", "UNCONFIGURED");
        }
        try {
            return platformClient.status((String) bill().get("month"));
        } catch (Exception e) {
            return Map.of("status", "UNKNOWN", "message", e.getMessage());
        }
    }

    private static Instant paidAt(Instant completedAt, Instant createdAt) {
        return completedAt != null ? completedAt : createdAt;
    }
}
