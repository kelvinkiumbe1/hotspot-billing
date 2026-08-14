package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.C2bPayment;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.C2bPaymentRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * PayBill (C2B) payments: customers pay any time using their PPPoE
 * username as the account number, and the system credits the matching
 * subscription automatically. Unmatched money is queued for the admin.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class C2bService {

    private final C2bPaymentRepository c2bPayments;
    private final SubscriberRepository subscribers;
    private final SubscriptionService subscriptionService;
    private final PaybillActivationService paybillActivation;
    private final SmsService smsService;
    private final AuditService audit;

    /** Handles a Safaricom confirmation payload. Idempotent by transactionId. */
    @Transactional
    public C2bPayment handleConfirmation(String transactionId, BigDecimal amount, String phoneNumber,
                                         String billRefNumber, String payerName) {
        Optional<C2bPayment> existing = c2bPayments.findByTransactionId(transactionId);
        if (existing.isPresent()) {
            log.info("Ignoring duplicate C2B confirmation {}", transactionId);
            return existing.get();
        }

        C2bPayment payment = C2bPayment.builder()
                .transactionId(transactionId)
                .amount(amount)
                .phoneNumber(phoneNumber)
                .billRefNumber(billRefNumber)
                .payerName(payerName)
                .build();

        Subscriber sub = resolveSubscriber(billRefNumber, phoneNumber);
        if (sub == null) {
            // Not a fixed-line customer. Before giving up and queueing this for
            // the admin, try to serve it as what it usually is: somebody on the
            // hotspot paying the paybill by hand because STK didn't work for
            // them. Zero-touch activation issues the pass and, where the
            // operator has enabled it, lets their device straight on.
            PaybillActivationService.Outcome outcome =
                    paybillActivation.activate(amount, phoneNumber, billRefNumber);
            if (outcome.activated()) {
                payment.setStatus(C2bPayment.Status.MATCHED);
                payment.setNote(outcome.note());
                return c2bPayments.save(payment);
            }
            payment.setStatus(C2bPayment.Status.UNMATCHED);
            payment.setNote(outcome.note() != null ? outcome.note()
                    : "No subscriber matched account '" + billRefNumber + "' or phone " + phoneNumber);
            audit.system("c2b.unmatched", "Unmatched PayBill payment " + transactionId + " of KES " + amount);
            log.warn("Unmatched C2B payment {} (ref '{}', phone {})", transactionId, billRefNumber, phoneNumber);
            return c2bPayments.save(payment);
        }

        int months = monthsFor(sub, amount);
        if (months < 1) {
            payment.setStatus(C2bPayment.Status.UNMATCHED);
            payment.setSubscriberId(sub.getId());
            payment.setNote("KES " + amount + " is less than one month (KES " + sub.getMonthlyFee() + ") for "
                    + sub.getPppoeUsername());
            smsService.trySend(phoneNumber,
                    "We received KES " + amount + " but your monthly fee is KES " + sub.getMonthlyFee()
                            + ". Please top up the balance or call support.");
            return c2bPayments.save(payment);
        }

        subscriptionService.creditExternalPayment(sub.getId(), months, amount, transactionId);
        payment.setStatus(C2bPayment.Status.MATCHED);
        payment.setSubscriberId(sub.getId());
        payment.setMonthsCredited(months);
        payment.setNote("Credited " + months + " month(s) to " + sub.getPppoeUsername());
        audit.system("c2b.matched", "PayBill " + transactionId + " credited " + months
                + " month(s) to " + sub.getPppoeUsername());
        return c2bPayments.save(payment);
    }

    /** Account number first (that is what we tell customers to use), then phone. */
    private Subscriber resolveSubscriber(String billRefNumber, String phoneNumber) {
        if (billRefNumber != null && !billRefNumber.isBlank()) {
            String ref = billRefNumber.trim();
            Optional<Subscriber> byUsername = subscribers.findByPppoeUsername(ref);
            if (byUsername.isPresent()) {
                return byUsername.get();
            }
            Optional<Subscriber> ci = subscribers.findAll().stream()
                    .filter(s -> s.getPppoeUsername().equalsIgnoreCase(ref))
                    .findFirst();
            if (ci.isPresent()) {
                return ci.get();
            }
        }
        List<Subscriber> byPhone = subscribers.findByPhoneNumber(phoneNumber);
        return byPhone.size() == 1 ? byPhone.get(0) : null;
    }

    /** Whole months the money covers (partial months are not credited). */
    private int monthsFor(Subscriber sub, BigDecimal amount) {
        if (sub.getMonthlyFee() == null || sub.getMonthlyFee().signum() <= 0) {
            return 0;
        }
        return amount.divide(sub.getMonthlyFee(), 0, RoundingMode.FLOOR).intValue();
    }

    @Transactional(readOnly = true)
    public List<C2bPayment> recent() {
        return c2bPayments.findTop200ByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<C2bPayment> unmatched() {
        return c2bPayments.findByStatusOrderByCreatedAtDesc(C2bPayment.Status.UNMATCHED);
    }

    /** Admin assigns an unmatched payment to a subscriber by hand. */
    @Transactional
    public C2bPayment applyManually(Long paymentId, Long subscriberId, int months, String actor) {
        C2bPayment payment = c2bPayments.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment: " + paymentId));
        if (payment.getStatus() == C2bPayment.Status.MATCHED
                || payment.getStatus() == C2bPayment.Status.APPLIED_MANUALLY) {
            throw new IllegalStateException("That payment has already been applied");
        }
        Subscriber sub = subscribers.findById(subscriberId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown subscriber: " + subscriberId));
        subscriptionService.creditExternalPayment(sub.getId(), months, payment.getAmount(), payment.getTransactionId());
        payment.setStatus(C2bPayment.Status.APPLIED_MANUALLY);
        payment.setSubscriberId(sub.getId());
        payment.setMonthsCredited(months);
        payment.setNote("Applied by " + actor + " to " + sub.getPppoeUsername());
        audit.record(actor, "c2b.apply", "Applied PayBill " + payment.getTransactionId()
                + " to " + sub.getPppoeUsername() + " (" + months + " month(s))");
        return c2bPayments.save(payment);
    }
}
