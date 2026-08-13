package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Monthly PPPoE subscriptions: create subscribers, take payments (M-Pesa
 * or cash), extend paidUntil, suspend/reactivate on the router, and send
 * the SMS notices. One month = 30 days.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private static final int DAYS_PER_MONTH = 30;

    private final SubscriberRepository subscribers;
    private final SubscriptionPaymentRepository payments;
    private final MikrotikService mikrotikService;
    private final MpesaService mpesaService;
    private final NotificationService notificationService;
    private final PortalSettingsService portalSettingsService;
    private final InvoiceService invoiceService;
    private final EtimsService etimsService;
    private final ReferralService referralService;

    @org.springframework.beans.factory.annotation.Value("${app.portal-url}")
    private String portalUrl;

    /** Master switch for failed-payment recovery (dunning). */
    @org.springframework.beans.factory.annotation.Value("${dunning.enabled:true}")
    private boolean dunningEnabled;

    /**
     * Hours to wait before each successive retry after the initial auto-renewal
     * prompt, as a comma list. Four entries → up to four extra attempts, the
     * last ~3 days out, then the chase stops.
     */
    @org.springframework.beans.factory.annotation.Value("${dunning.retry-hours:4,12,24,48}")
    private String dunningRetryHours;

    /** Master switch for the win-back re-engagement series. */
    @org.springframework.beans.factory.annotation.Value("${winback.enabled:true}")
    private boolean winbackEnabled;

    /**
     * Days after a customer lapses to send each win-back message, as a comma
     * list. The first entry should sit past the dunning window so the two don't
     * overlap. Stages beyond the third reuse the final message.
     */
    @org.springframework.beans.factory.annotation.Value("${winback.days:3,10,21}")
    private String winbackDays;

    @Transactional
    public Subscriber create(String fullName, String phoneNumber, String pppoeUsername,
                             String pppoePassword, String bandwidth, BigDecimal monthlyFee, int initialMonths) {
        return create(fullName, phoneNumber, pppoeUsername, pppoePassword, bandwidth, monthlyFee,
                initialMonths, SubscriptionPayment.Method.CASH, null);
    }

    @Transactional
    public Subscriber create(String fullName, String phoneNumber, String pppoeUsername,
                             String pppoePassword, String bandwidth, BigDecimal monthlyFee,
                             int initialMonths, SubscriptionPayment.Method initialMethod) {
        return create(fullName, phoneNumber, pppoeUsername, pppoePassword, bandwidth, monthlyFee,
                initialMonths, initialMethod, null);
    }

    /**
     * Creates a subscriber. With CASH the initial months are credited
     * immediately; with MPESA an STK prompt is sent and the months are
     * credited when the payment callback arrives.
     */
    @Transactional
    public Subscriber create(String fullName, String phoneNumber, String pppoeUsername,
                             String pppoePassword, String bandwidth, BigDecimal monthlyFee,
                             int initialMonths, SubscriptionPayment.Method initialMethod, String createdBy) {
        subscribers.findByPppoeUsername(pppoeUsername).ifPresent(existing -> {
            throw new IllegalArgumentException("PPPoE username already taken: " + pppoeUsername);
        });
        boolean cash = initialMethod != SubscriptionPayment.Method.MPESA;
        Subscriber sub = subscribers.save(Subscriber.builder()
                .fullName(fullName)
                .phoneNumber(phoneNumber)
                .pppoeUsername(pppoeUsername)
                .pppoePassword(pppoePassword)
                .bandwidth(bandwidth)
                .monthlyFee(monthlyFee)
                .paidUntil(cash
                        ? Instant.now().plus((long) initialMonths * DAYS_PER_MONTH, ChronoUnit.DAYS)
                        : Instant.now())
                .lastPaymentMethod(cash && initialMonths > 0 ? "CASH" : null)
                .lastPaymentAt(cash && initialMonths > 0 ? Instant.now() : null)
                .createdBy(createdBy)
                .build());
        mikrotikService.provisionPppoe(sub);
        if (cash && initialMonths > 0) {
            BigDecimal paid = monthlyFee.multiply(BigDecimal.valueOf(initialMonths));
            payments.save(SubscriptionPayment.builder()
                    .subscriber(sub)
                    .amount(paid)
                    .months(initialMonths)
                    .method(SubscriptionPayment.Method.CASH)
                    .status(SubscriptionPayment.Status.SUCCESS)
                    .completedAt(Instant.now())
                    .build());
            // Fiscalise this first sale too (the MPESA path fiscalises via the
            // callback → extend; this cash credit doesn't go through extend).
            try {
                etimsService.recordSale(com.spalimited.hotspotbilling.domain.TaxInvoice.Source.SUBSCRIPTION,
                        phoneNumber, "Internet subscription — " + initialMonths + " month(s)", paid);
            } catch (Exception e) {
                log.warn("eTIMS record failed for new subscriber {}: {}", pppoeUsername, e.getMessage());
            }
        } else if (!cash && initialMonths > 0) {
            initiateStk(sub.getId(), initialMonths);
        }
        return sub;
    }

    @Transactional
    public SubscriptionPayment recordCashPayment(Long subscriberId, int months) {
        Subscriber sub = get(subscriberId);
        SubscriptionPayment payment = payments.save(SubscriptionPayment.builder()
                .subscriber(sub)
                .amount(sub.getMonthlyFee().multiply(BigDecimal.valueOf(months)))
                .months(months)
                .method(SubscriptionPayment.Method.CASH)
                .status(SubscriptionPayment.Status.SUCCESS)
                .completedAt(Instant.now())
                .build());
        extend(sub, months, "CASH");
        return payment;
    }

    /** Sends an M-Pesa STK prompt to the subscriber's phone for N months. */
    @Transactional
    public SubscriptionPayment initiateStk(Long subscriberId, int months) {
        Subscriber sub = get(subscriberId);
        BigDecimal amount = sub.getMonthlyFee().multiply(BigDecimal.valueOf(months));
        String checkoutRequestId = mpesaService.stkPush(sub.getPhoneNumber(), amount, "PPPOE-" + sub.getId());
        return payments.save(SubscriptionPayment.builder()
                .subscriber(sub)
                .amount(amount)
                .months(months)
                .method(SubscriptionPayment.Method.MPESA)
                .checkoutRequestId(checkoutRequestId)
                .build());
    }

    /**
     * Handles a Daraja callback that belongs to a subscription payment.
     * Returns false when the CheckoutRequestID is not ours.
     */
    @Transactional
    public boolean handleStkCallback(String checkoutRequestId, int resultCode, String receiptNumber) {
        SubscriptionPayment payment = payments.findByCheckoutRequestId(checkoutRequestId).orElse(null);
        if (payment == null) {
            return false;
        }
        if (payment.getStatus() != SubscriptionPayment.Status.PENDING) {
            return true;
        }
        payment.setCompletedAt(Instant.now());
        if (resultCode != 0) {
            payment.setStatus(SubscriptionPayment.Status.FAILED);
            return true;
        }
        payment.setStatus(SubscriptionPayment.Status.SUCCESS);
        payment.setMpesaReceiptNumber(receiptNumber);
        extend(payment.getSubscriber(), payment.getMonths(), "MPESA");
        return true;
    }

    private void extend(Subscriber sub, int months, String method) {
        Instant base = sub.getPaidUntil().isAfter(Instant.now()) ? sub.getPaidUntil() : Instant.now();
        sub.setPaidUntil(base.plus((long) months * DAYS_PER_MONTH, ChronoUnit.DAYS));
        sub.setLastPaymentMethod(method);
        sub.setLastPaymentAt(Instant.now());
        if (sub.getStatus() == Subscriber.Status.SUSPENDED) {
            mikrotikService.setPppoeEnabled(sub, true);
            sub.setStatus(Subscriber.Status.ACTIVE);
        }
        clearDunning(sub); // a payment landed — stop chasing this customer
        clearWinback(sub); // and stop any come-back series; they're back
        subscribers.save(sub);
        invoiceService.settleOldestUnpaid(sub.getId(), method);
        notify(com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.SUBSCRIPTION_PAID, sub,
                java.util.Map.of("date", sub.getPaidUntil().toString().substring(0, 10)));
        // Fiscalise for KRA (no-op until eTIMS is configured).
        try {
            etimsService.recordSale(com.spalimited.hotspotbilling.domain.TaxInvoice.Source.SUBSCRIPTION,
                    sub.getPhoneNumber(), "Internet subscription — " + months + " month(s)",
                    sub.getMonthlyFee().multiply(BigDecimal.valueOf(months)));
        } catch (Exception e) {
            log.warn("eTIMS record failed for subscriber {}: {}", sub.getPppoeUsername(), e.getMessage());
        }
        // Settle a pending referral if this is the referred customer's first buy.
        try {
            referralService.settleIfPending(sub.getPhoneNumber());
        } catch (Exception e) {
            log.warn("Referral settle failed for subscriber {}: {}", sub.getPppoeUsername(), e.getMessage());
        }
        log.info("Extended subscriber {} by {} month(s) to {}", sub.getPppoeUsername(), months, sub.getPaidUntil());
    }

    /**
     * Credits a payment that arrived outside the STK flow — a PayBill
     * (C2B) deposit or an admin applying an unmatched one by hand.
     */
    @Transactional
    public SubscriptionPayment creditExternalPayment(Long subscriberId, int months,
                                                     BigDecimal amount, String receiptNumber) {
        Subscriber sub = get(subscriberId);
        SubscriptionPayment payment = payments.save(SubscriptionPayment.builder()
                .subscriber(sub)
                .amount(amount)
                .months(months)
                .method(SubscriptionPayment.Method.MPESA)
                .status(SubscriptionPayment.Status.SUCCESS)
                .mpesaReceiptNumber(receiptNumber)
                .completedAt(Instant.now())
                .build());
        extend(sub, months, "MPESA");
        return payment;
    }

    /**
     * Goodwill/manual extension without a payment — hours, days or months
     * (e.g. compensation for downtime). Reactivates a suspended account.
     */
    @Transactional
    public Subscriber extendManually(Long id, int amount, String unit) {
        Subscriber sub = get(id);
        Instant base = sub.getPaidUntil().isAfter(Instant.now()) ? sub.getPaidUntil() : Instant.now();
        Instant newPaidUntil = switch (unit == null ? "" : unit.toUpperCase()) {
            case "HOURS" -> base.plus(amount, ChronoUnit.HOURS);
            case "DAYS" -> base.plus(amount, ChronoUnit.DAYS);
            case "MONTHS" -> base.plus((long) amount * DAYS_PER_MONTH, ChronoUnit.DAYS);
            default -> throw new IllegalArgumentException("Unit must be HOURS, DAYS or MONTHS");
        };
        sub.setPaidUntil(newPaidUntil);
        if (sub.getStatus() == Subscriber.Status.SUSPENDED) {
            mikrotikService.setPppoeEnabled(sub, true);
            sub.setStatus(Subscriber.Status.ACTIVE);
        }
        notify(com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.SUBSCRIPTION_EXTENDED, sub,
                java.util.Map.of("date", newPaidUntil.toString().substring(0, 10)));
        log.info("Manually extended subscriber {} by {} {}", sub.getPppoeUsername(), amount, unit);
        return subscribers.save(sub);
    }

    /**
     * Pushes every active subscriber's expiry back by an outage's duration,
     * so nobody pays for time the network was down. Returns how many
     * accounts were credited. Suspended accounts are left alone — they were
     * already off for their own reasons, not the outage.
     */
    @Transactional
    public int compensateForOutage(java.time.Duration downtime) {
        long minutes = downtime.toMinutes();
        if (minutes <= 0) {
            return 0;
        }
        int credited = 0;
        for (Subscriber sub : subscribers.findByStatus(Subscriber.Status.ACTIVE)) {
            if (sub.getPaidUntil() == null) {
                continue;
            }
            sub.setPaidUntil(sub.getPaidUntil().plus(minutes, ChronoUnit.MINUTES));
            subscribers.save(sub);
            credited++;
        }
        if (credited > 0) {
            log.info("Outage compensation: extended {} subscriber(s) by {} minute(s)", credited, minutes);
        }
        return credited;
    }

    @Transactional
    public Subscriber suspend(Long id) {
        Subscriber sub = get(id);
        mikrotikService.setPppoeEnabled(sub, false);
        sub.setStatus(Subscriber.Status.SUSPENDED);
        return subscribers.save(sub);
    }

    @Transactional
    public Subscriber activate(Long id) {
        Subscriber sub = get(id);
        mikrotikService.setPppoeEnabled(sub, true);
        sub.setStatus(Subscriber.Status.ACTIVE);
        return subscribers.save(sub);
    }

    @Transactional
    public void delete(Long id) {
        Subscriber sub = get(id);
        mikrotikService.removePppoe(sub);
        payments.deleteBySubscriberId(id);
        subscribers.delete(sub);
    }

    // --- Self-service (public payment page) ---

    /** Accounts registered under this phone number, for the public pay page. */
    @Transactional(readOnly = true)
    public java.util.List<Subscriber> findByPhone(String phoneNumber) {
        return subscribers.findByPhoneNumber(phoneNumber);
    }

    /**
     * Public self-payment: the phone number must match the account on
     * record — the STK prompt (and M-Pesa PIN) is the real verification.
     */
    @Transactional
    public SubscriptionPayment selfPay(Long subscriberId, String phoneNumber, int months) {
        Subscriber sub = get(subscriberId);
        if (!sub.getPhoneNumber().equals(phoneNumber)) {
            throw new IllegalArgumentException("That phone number does not match this account");
        }
        return initiateStk(subscriberId, months);
    }

    @Transactional(readOnly = true)
    public SubscriptionPayment getPayment(Long paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment: " + paymentId));
    }

    /** Hourly sweep: suspend lapsed accounts, remind those expiring within 3 days. */
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        for (Subscriber sub : subscribers.findByStatus(Subscriber.Status.ACTIVE)) {
            if (sub.getPaidUntil().isBefore(now)) {
                try {
                    mikrotikService.setPppoeEnabled(sub, false);
                } catch (Exception e) {
                    log.warn("Could not disable {} on router: {}", sub.getPppoeUsername(), e.getMessage());
                }
                sub.setStatus(Subscriber.Status.SUSPENDED);
                startWinback(sub); // begin the come-back series for this lapse
                subscribers.save(sub);
                notify(com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.SUBSCRIPTION_SUSPENDED, sub,
                        java.util.Map.of("amount", sub.getMonthlyFee().toPlainString(),
                                "payUrl", portalUrl + "/pay"));
                log.info("Suspended lapsed subscriber {}", sub.getPppoeUsername());
            } else {
                if (sub.getPaidUntil().isBefore(now.plus(3, ChronoUnit.DAYS))
                        && !sub.getPaidUntil().equals(sub.getRemindedForExpiry())) {
                    notify(com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.EXPIRY_REMINDER, sub,
                            java.util.Map.of("date", sub.getPaidUntil().toString().substring(0, 10),
                                    "amount", sub.getMonthlyFee().toPlainString(),
                                    "payUrl", portalUrl + "/pay"));
                    sub.setRemindedForExpiry(sub.getPaidUntil());
                    subscribers.save(sub);
                }
                // Auto-renewal: one day before expiry, fire an STK prompt so the
                // customer only has to enter their M-Pesa PIN. Once per cycle.
                if (sub.getPaidUntil().isBefore(now.plus(1, ChronoUnit.DAYS))
                        && !sub.getPaidUntil().equals(sub.getAutoStkForExpiry())) {
                    sub.setAutoStkForExpiry(sub.getPaidUntil());
                    subscribers.save(sub);
                    try {
                        initiateStk(sub.getId(), 1);
                        startDunning(sub); // begin the retry clock for this renewal
                        log.info("Auto-renewal STK sent to subscriber {}", sub.getPppoeUsername());
                    } catch (Exception e) {
                        log.warn("Auto-renewal STK for {} failed: {}", sub.getPppoeUsername(), e.getMessage());
                    }
                }
            }
        }
    }

    // --- Dunning: recover failed auto-renewals instead of letting them lapse ---

    /**
     * Begins the retry clock for a subscriber whose auto-renewal STK has just
     * been sent. The initial prompt counts as attempt 0; the first retry is
     * scheduled for the first configured interval from now.
     */
    private void startDunning(Subscriber sub) {
        java.util.List<Long> schedule = retrySchedule();
        if (!dunningEnabled || schedule.isEmpty()) {
            return;
        }
        sub.setDunningCycle(sub.getPaidUntil());
        sub.setDunningAttempts(0);
        sub.setDunningNextAt(Instant.now().plus(schedule.get(0), ChronoUnit.HOURS));
        subscribers.save(sub);
    }

    private void clearDunning(Subscriber sub) {
        sub.setDunningCycle(null);
        sub.setDunningAttempts(0);
        sub.setDunningNextAt(null);
    }

    /**
     * Re-prompts subscribers whose auto-renewal wasn't paid, on the configured
     * escalating schedule. Each retry fires a fresh STK (so the customer only
     * enters their PIN) plus a "tap to pay" message with the portal link. Stops
     * as soon as they pay ({@link #extend} clears the cycle) or the attempts run
     * out. Safe to run often; only due rows are touched.
     */
    @Transactional
    public void runDunning() {
        if (!dunningEnabled) {
            return;
        }
        java.util.List<Long> schedule = retrySchedule();
        if (schedule.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (Subscriber sub : subscribers.findByDunningCycleIsNotNullAndDunningNextAtLessThanEqual(now)) {
            // Paid since we last looked (cycle moved forward) — stop chasing.
            if (sub.getPaidUntil() != null && sub.getDunningCycle() != null
                    && sub.getPaidUntil().isAfter(sub.getDunningCycle())) {
                clearDunning(sub);
                subscribers.save(sub);
                continue;
            }
            int attempt = sub.getDunningAttempts(); // 0-based index of the retry to send now
            if (attempt >= schedule.size()) {
                clearDunning(sub);
                subscribers.save(sub);
                log.info("Dunning exhausted for subscriber {}", sub.getPppoeUsername());
                continue;
            }
            try {
                initiateStk(sub.getId(), 1);
            } catch (Exception e) {
                log.warn("Dunning STK for {} failed: {}", sub.getPppoeUsername(), e.getMessage());
            }
            notify(com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.DUNNING_RETRY, sub,
                    java.util.Map.of("amount", sub.getMonthlyFee().toPlainString(),
                            "payUrl", portalUrl + "/pay"));
            int next = attempt + 1;
            if (next >= schedule.size()) {
                clearDunning(sub); // that was the final retry — nothing more to try
            } else {
                sub.setDunningAttempts(next);
                sub.setDunningNextAt(now.plus(schedule.get(next), ChronoUnit.HOURS));
            }
            subscribers.save(sub);
            log.info("Dunning retry {} sent to subscriber {}", next, sub.getPppoeUsername());
        }
    }

    private java.util.List<Long> retrySchedule() {
        return parseHours(dunningRetryHours);
    }

    /** Parses a comma list of positive numbers, ignoring blanks/garbage. */
    private java.util.List<Long> parseHours(String csv) {
        java.util.List<Long> out = new java.util.ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                long n = Long.parseLong(s);
                if (n > 0) {
                    out.add(n);
                }
            } catch (NumberFormatException ignored) {
                // skip a malformed entry rather than break the whole schedule
            }
        }
        return out;
    }

    // --- Win-back: re-engage customers who stayed lapsed after dunning ---

    /**
     * Starts the win-back series for a customer who has just lapsed. Anchored to
     * the lapse (their expired paidUntil) so each message lands a fixed number of
     * days later and a fresh lapse resets the series.
     */
    private void startWinback(Subscriber sub) {
        java.util.List<Long> days = winbackSchedule();
        if (!winbackEnabled || days.isEmpty()) {
            return;
        }
        sub.setWinbackCycle(sub.getPaidUntil());
        sub.setWinbackStage(0);
        sub.setWinbackNextAt(sub.getPaidUntil().plus(days.get(0), ChronoUnit.DAYS));
    }

    private void clearWinback(Subscriber sub) {
        sub.setWinbackCycle(null);
        sub.setWinbackStage(0);
        sub.setWinbackNextAt(null);
    }

    /**
     * Sends the next due win-back message to each lapsed customer, escalating
     * copy per stage, until they return ({@link #extend} clears the series) or
     * the stages run out. No STK here — it's re-engagement, so it just carries a
     * pay link; the customer chooses to come back.
     */
    @Transactional
    public void runWinback() {
        if (!winbackEnabled) {
            return;
        }
        java.util.List<Long> days = winbackSchedule();
        if (days.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (Subscriber sub : subscribers.findByWinbackCycleIsNotNullAndWinbackNextAtLessThanEqual(now)) {
            // Came back since (paid, so cycle moved) — stop.
            if (sub.getPaidUntil() != null && sub.getWinbackCycle() != null
                    && sub.getPaidUntil().isAfter(sub.getWinbackCycle())) {
                clearWinback(sub);
                subscribers.save(sub);
                continue;
            }
            int stage = sub.getWinbackStage(); // 0-based stage to send now
            if (stage >= days.size()) {
                clearWinback(sub);
                subscribers.save(sub);
                continue;
            }
            notify(winbackTemplate(stage), sub,
                    java.util.Map.of("amount", sub.getMonthlyFee().toPlainString(),
                            "payUrl", portalUrl + "/pay",
                            "date", sub.getWinbackCycle().toString().substring(0, 10)));
            int next = stage + 1;
            if (next >= days.size()) {
                clearWinback(sub); // final message sent — series complete
            } else {
                sub.setWinbackStage(next);
                sub.setWinbackNextAt(sub.getWinbackCycle().plus(days.get(next), ChronoUnit.DAYS));
            }
            subscribers.save(sub);
            log.info("Win-back message {} sent to subscriber {}", stage + 1, sub.getPppoeUsername());
        }
    }

    /** First/second/final copy by stage; extra stages reuse the final message. */
    private com.spalimited.hotspotbilling.domain.NotificationTemplate.Key winbackTemplate(int stage) {
        return switch (stage) {
            case 0 -> com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.WINBACK_FIRST;
            case 1 -> com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.WINBACK_SECOND;
            default -> com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.WINBACK_FINAL;
        };
    }

    private java.util.List<Long> winbackSchedule() {
        return parseHours(winbackDays);
    }

    /** Sends a templated message, always supplying the business name. */
    private void notify(com.spalimited.hotspotbilling.domain.NotificationTemplate.Key key,
                        Subscriber sub, java.util.Map<String, String> values) {
        java.util.Map<String, String> merged = new java.util.HashMap<>(values);
        merged.put("business", portalSettingsService.settings().getBusinessName());
        notificationService.send(key, sub.getPhoneNumber(), merged);
    }

    private Subscriber get(Long id) {
        return subscribers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown subscriber: " + id));
    }
}
