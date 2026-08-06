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

    @org.springframework.beans.factory.annotation.Value("${app.portal-url}")
    private String portalUrl;

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
            payments.save(SubscriptionPayment.builder()
                    .subscriber(sub)
                    .amount(monthlyFee.multiply(BigDecimal.valueOf(initialMonths)))
                    .months(initialMonths)
                    .method(SubscriptionPayment.Method.CASH)
                    .status(SubscriptionPayment.Status.SUCCESS)
                    .completedAt(Instant.now())
                    .build());
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
        subscribers.save(sub);
        invoiceService.settleOldestUnpaid(sub.getId(), method);
        notify(com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.SUBSCRIPTION_PAID, sub,
                java.util.Map.of("date", sub.getPaidUntil().toString().substring(0, 10)));
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
                        log.info("Auto-renewal STK sent to subscriber {}", sub.getPppoeUsername());
                    } catch (Exception e) {
                        log.warn("Auto-renewal STK for {} failed: {}", sub.getPppoeUsername(), e.getMessage());
                    }
                }
            }
        }
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
