package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
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
public class PlatformBillingController {

    private final PaymentRepository payments;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final SubscriberRepository subscribers;
    private final StaffUserRepository staff;

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

    private static Instant paidAt(Instant completedAt, Instant createdAt) {
        return completedAt != null ? completedAt : createdAt;
    }
}
