package com.spalimited.hotspotbilling.service;

import tools.jackson.databind.JsonNode;
import com.spalimited.hotspotbilling.domain.CustomPlanSettings;
import com.spalimited.hotspotbilling.domain.ManualClaim;
import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.ManualClaimRepository;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the buy flow: STK push -> Daraja callback -> voucher issued.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PlanRepository planRepository;
    private final MpesaService mpesaService;
    private final VoucherService voucherService;
    private final EtimsService etimsService;
    private final ReferralService referralService;
    private final CustomPlanService customPlanService;
    private final PromotionService promotionService;
    private final NotificationService notificationService;
    private final PortalSettingsService portalSettingsService;
    private final SubscriptionService subscriptionService;
    private final WebhookService webhookService;
    private final LoyaltyService loyaltyService;
    private final SmsService smsService;
    private final ManualClaimRepository manualClaims;
    private final PaymentGatewayService gatewayService;
    private final CreditService creditService;
    private final VoucherRepository voucherRepository;

    /** Give the async callback a head start before the sweep queries Daraja. */
    private static final long RECONCILE_GRACE_SECONDS = 45;
    /** Past this, a still-pending payment is failed so the portal stops waiting. */
    private static final long RECONCILE_TIMEOUT_SECONDS = 15 * 60;

    @Transactional(readOnly = true)
    public Payment get(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment: " + id));
    }

    @Transactional
    public Payment initiateStkPush(String phoneNumber, Long planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + planId));
        if (!plan.isActive()) {
            throw new IllegalStateException("Plan is not active");
        }
        BigDecimal price = promotionService.apply(plan.getPrice());
        // Anything taken on credit rides on this purchase. Collecting it as
        // part of the amount the customer is already approving is the whole
        // recovery mechanism — there is no separate debt to chase afterwards.
        BigDecimal owed = creditService.outstandingFor(phoneNumber);
        BigDecimal charge = price.add(owed);
        String checkoutRequestId = mpesaService.stkPush(phoneNumber, charge, "HOTSPOT-" + planId);
        Payment payment = Payment.builder()
                .phoneNumber(phoneNumber)
                .amount(charge)
                .plan(plan)
                .checkoutRequestId(checkoutRequestId)
                .build();
        return paymentRepository.save(payment);
    }

    /**
     * Pay-per-minute purchase: the customer chose an exact number of
     * minutes; the price comes from the admin-configured hourly rate.
     */
    @Transactional
    public Payment initiateCustomStkPush(String phoneNumber, int minutes) {
        CustomPlanSettings settings = customPlanService.settings();
        if (!settings.isEnabled()) {
            throw new IllegalStateException("Custom time passes are not available right now");
        }
        if (minutes < settings.getMinMinutes() || minutes > settings.getMaxMinutes()) {
            throw new IllegalArgumentException(
                    "Choose between " + settings.getMinMinutes() + " and " + settings.getMaxMinutes() + " minutes");
        }
        BigDecimal price = promotionService.apply(customPlanService.priceFor(minutes, settings));
        Plan plan = customPlanService.systemPlan(settings);
        String checkoutRequestId = mpesaService.stkPush(phoneNumber, price, "HOTSPOT-CUSTOM");
        Payment payment = Payment.builder()
                .phoneNumber(phoneNumber)
                .amount(price)
                .plan(plan)
                .customMinutes(minutes)
                .checkoutRequestId(checkoutRequestId)
                .build();
        return paymentRepository.save(payment);
    }

    /**
     * Handles the async Daraja STK callback. On success, issues a voucher
     * and provisions it on the router.
     */
    @Transactional
    public void handleStkCallback(JsonNode callbackBody) {
        JsonNode stkCallback = callbackBody.path("Body").path("stkCallback");
        String checkoutRequestId = stkCallback.path("CheckoutRequestID").asText();
        int resultCode = stkCallback.path("ResultCode").asInt(-1);

        String receiptNumber = null;
        java.math.BigDecimal paidAmount = null;
        for (JsonNode item : stkCallback.path("CallbackMetadata").path("Item")) {
            String name = item.path("Name").asText();
            if ("MpesaReceiptNumber".equals(name)) {
                receiptNumber = item.path("Value").asText();
            } else if ("Amount".equals(name)) {
                try {
                    paidAmount = new java.math.BigDecimal(item.path("Value").asText());
                } catch (RuntimeException ignored) {
                    // Leave null; the amount check below treats that as a mismatch.
                }
            }
        }

        Payment payment = paymentRepository.findByCheckoutRequestId(checkoutRequestId).orElse(null);
        if (payment == null) {
            // Not a voucher purchase — maybe a PPPoE subscription payment.
            if (subscriptionService.handleStkCallback(checkoutRequestId, resultCode, receiptNumber)) {
                return;
            }
            log.warn("Callback for unknown CheckoutRequestID {}", checkoutRequestId);
            return;
        }
        if (payment.getStatus() != Payment.Status.PENDING) {
            log.info("Ignoring duplicate callback for payment {}", payment.getId());
            return;
        }

        if (resultCode != 0) {
            markFailed(payment, "callback result: " + stkCallback.path("ResultDesc").asText());
            return;
        }

        // Confirm the money that arrived matches what we asked for. With STK
        // the amount is server-set so a genuine callback always matches; a
        // mismatch means either a forged callback that slipped the source
        // check or an integration fault, and either way must not mint a
        // voucher. Recorded as FAILED so it surfaces in reconciliation.
        java.math.BigDecimal expected = payment.getAmount();
        if (expected != null && (paidAmount == null || paidAmount.compareTo(expected) != 0)) {
            markFailed(payment, "amount mismatch: expected " + expected + " but callback reported " + paidAmount);
            return;
        }

        completeSuccess(payment, receiptNumber);
    }

    /**
     * Settles a payment a card processor has told us about.
     *
     * <p>Deliberately the same shape as the M-Pesa callback path, and it funnels
     * into the same {@code completeSuccess}: whichever rail took the money, the
     * voucher is issued, the code sent, loyalty awarded and webhooks fired by
     * one piece of code. The alternative — a second issue path per provider —
     * is how systems end up with vouchers that exist but were never texted.
     *
     * @param providerRef what the processor calls the transaction
     * @param reference   what we called it when we started the charge
     */
    @Transactional
    public void settleFromProvider(String provider, String providerRef, String reference,
                                   boolean paid, java.math.BigDecimal amount, String receipt,
                                   String failureReason) {
        // Either identifier may be the one we stored, depending on the rail, so
        // both are tried before giving up.
        Payment payment = paymentRepository.findByCheckoutRequestId(
                        reference == null ? "" : reference)
                .or(() -> paymentRepository.findByCheckoutRequestId(
                        providerRef == null ? "" : providerRef))
                .orElse(null);
        if (payment == null) {
            log.warn("{} webhook for unknown reference {} / {}", provider, reference, providerRef);
            return;
        }
        if (payment.getStatus() != Payment.Status.PENDING) {
            // Every one of these providers retries until it gets a 2xx, so a
            // repeat is the normal case and must not issue a second voucher.
            log.info("Ignoring repeat {} webhook for payment {}", provider, payment.getId());
            return;
        }
        if (!paid) {
            markFailed(payment, provider + ": " + (failureReason == null ? "not paid" : failureReason));
            return;
        }

        // The same amount check the M-Pesa path makes, and for a stronger
        // reason here: Flutterwave's webhook is authenticated by a shared header
        // rather than a signature over the body, so the body is the part we
        // trust least. A mismatch is recorded as FAILED so reconciliation sees
        // it rather than a voucher being minted for the wrong money.
        java.math.BigDecimal expected = payment.getAmount();
        if (expected != null && (amount == null || amount.compareTo(expected) != 0)) {
            markFailed(payment, provider + " reported " + amount + " but we asked for " + expected);
            return;
        }
        completeSuccess(payment, receipt);
    }

    /**
     * The single place a payment turns into a voucher: marks it SUCCESS, issues
     * the pass, texts the code, fires webhooks and awards loyalty. Both the
     * live callback and the reconciliation sweep funnel through here, so a
     * payment recovered after a lost callback is treated identically to one
     * that arrived normally. Receipt is null when recovered via status query,
     * which does not return it.
     */
    private Voucher completeSuccess(Payment payment, String receiptNumber) {
        payment.setCompletedAt(Instant.now());
        payment.setMpesaReceiptNumber(receiptNumber);
        payment.setStatus(Payment.Status.SUCCESS);
        Voucher voucher = payment.getCustomMinutes() != null
                ? voucherService.issueCustom(payment.getPlan(), payment.getPhoneNumber(), payment.getCustomMinutes())
                : voucherService.issue(payment.getPlan(), payment.getPhoneNumber());
        payment.setVoucher(voucher);
        paymentRepository.save(payment);
        log.info("Payment {} succeeded, voucher {} issued", payment.getId(), voucher.getCode());

        // The amount charged already included anything owed on a pay-later
        // pass, so the debt is settled the moment the money lands.
        creditService.settle(payment.getPhoneNumber(), "Payment #" + payment.getId());

        notificationService.send(
                NotificationTemplate.Key.VOUCHER_ISSUED,
                payment.getPhoneNumber(),
                Map.of(
                        "business", portalSettingsService.settings().getBusinessName(),
                        "code", voucher.getCode()));

        String planName = voucher.getPlan() != null ? voucher.getPlan().getName() : "Custom";
        webhookService.dispatch("payment.received", Map.of(
                "paymentId", payment.getId(),
                "amount", payment.getAmount(),
                "phone", payment.getPhoneNumber(),
                "plan", planName,
                "receipt", receiptNumber == null ? "" : receiptNumber));
        webhookService.dispatch("voucher.generated", Map.of(
                "code", voucher.getCode(),
                "plan", planName,
                "phone", payment.getPhoneNumber()));

        // Reward the customer for the purchase (no-op if loyalty is off).
        loyaltyService.earn(payment.getPhoneNumber(), payment.getAmount());

        // Fiscalise the sale for KRA (no-op until eTIMS is configured).
        try {
            etimsService.recordSale(com.spalimited.hotspotbilling.domain.TaxInvoice.Source.HOTSPOT,
                    payment.getPhoneNumber(), "Hotspot: " + planName, payment.getAmount());
        } catch (Exception e) {
            log.warn("eTIMS record failed for payment {}: {}", payment.getId(), e.getMessage());
        }
        // Settle a pending referral if this is the referred customer's first buy.
        try {
            referralService.settleIfPending(payment.getPhoneNumber());
        } catch (Exception e) {
            log.warn("Referral settle failed for payment {}: {}", payment.getId(), e.getMessage());
        }
        return voucher;
    }

    private void markFailed(Payment payment, String reason) {
        payment.setCompletedAt(Instant.now());
        payment.setStatus(Payment.Status.FAILED);
        paymentRepository.save(payment);
        log.info("Payment {} failed: {}", payment.getId(), reason);
    }

    /**
     * The safety net for lost callbacks. For each payment still PENDING past a
     * short grace period, asks Daraja the real outcome and issues the voucher
     * (or marks it failed) — so a callback that never arrived can no longer
     * strand a customer who paid. Payments still undecided past the timeout are
     * failed so the portal stops waiting. Run on a schedule by
     * {@code PaymentReconcileJob}.
     */
    @Transactional
    public int reconcilePending() {
        Instant now = Instant.now();
        Instant graceCutoff = now.minusSeconds(RECONCILE_GRACE_SECONDS);
        Instant timeoutCutoff = now.minusSeconds(RECONCILE_TIMEOUT_SECONDS);
        int settled = 0;
        for (Payment p : paymentRepository.findByStatus(Payment.Status.PENDING)) {
            Instant created = p.getCreatedAt();
            if (created == null || created.isAfter(graceCutoff)) {
                continue; // too fresh — the callback may still be on its way
            }
            Integer result = mpesaService.queryStkStatus(p.getCheckoutRequestId());
            if (result == null) {
                if (created.isBefore(timeoutCutoff)) {
                    markFailed(p, "timed out with no M-Pesa result");
                    settled++;
                }
                continue;
            }
            if (result == 0) {
                completeSuccess(p, null);
            } else {
                markFailed(p, "M-Pesa result code " + result);
            }
            settled++;
        }
        return settled;
    }

    /** What a phone-number recovery attempt resolved to, for the portal message. */
    public enum RecoveryResult { SENT, STILL_PENDING, FAILED, NONE, NO_SMS }

    /**
     * "I paid but wasn't connected." Given the phone number a customer paid
     * with, settles any still-pending payment on demand (querying Daraja), then
     * re-sends the voucher code — always by SMS to that same number, never in
     * the response, so nobody can recover a voucher paid for by someone else.
     */
    @Transactional
    public RecoveryResult recoverByPhone(String phoneNumber) {
        List<Payment> payments = paymentRepository.findByPhoneNumberOrderByCreatedAtDesc(phoneNumber);
        if (payments.isEmpty()) {
            return RecoveryResult.NONE;
        }

        // First, try to settle a pending payment on demand — the customer may
        // have paid seconds ago and the callback simply never arrived.
        for (Payment p : payments) {
            if (p.getStatus() != Payment.Status.PENDING) {
                continue;
            }
            Integer result = mpesaService.queryStkStatus(p.getCheckoutRequestId());
            if (result != null && result == 0) {
                if (!smsService.isEnabled()) {
                    completeSuccess(p, null); // voucher issued; SMS is a no-op
                    return RecoveryResult.NO_SMS;
                }
                completeSuccess(p, null); // issues the voucher and texts the code
                return RecoveryResult.SENT;
            } else if (result != null) {
                markFailed(p, "M-Pesa result code " + result);
            }
        }

        // Otherwise resend an existing, still-usable voucher to the payer.
        for (Payment p : payments) {
            if (p.getStatus() == Payment.Status.SUCCESS && p.getVoucher() != null) {
                Voucher v = p.getVoucher();
                if (v.getStatus() == Voucher.Status.EXPIRED || v.isExhausted()) {
                    continue;
                }
                if (!smsService.isEnabled()) {
                    return RecoveryResult.NO_SMS;
                }
                notificationService.send(
                        NotificationTemplate.Key.VOUCHER_ISSUED, phoneNumber,
                        Map.of(
                                "business", portalSettingsService.settings().getBusinessName(),
                                "code", v.getCode()));
                return RecoveryResult.SENT;
            }
        }

        boolean anyPending = payments.stream().anyMatch(p -> p.getStatus() == Payment.Status.PENDING);
        return anyPending ? RecoveryResult.STILL_PENDING : RecoveryResult.FAILED;
    }

    // --- Verify by M-Pesa code (Transaction Status API) ---

    /** How a "verify by M-Pesa code" request was answered. */
    public enum ClaimResult { CHECKING, ALREADY_ACTIVE, EXPIRED, UNAVAILABLE }

    /** The answer plus, for an already-active pass, how much time is left. */
    public record ClaimOutcome(ClaimResult result, Integer remainingMinutes) {
        static ClaimOutcome of(ClaimResult r) {
            return new ClaimOutcome(r, null);
        }
    }

    /**
     * Verifies a pasted M-Pesa code (the code alone — the customer can paste
     * the whole confirmation SMS and the caller scans the code out of it).
     * Three outcomes:
     * <ul>
     *   <li>never claimed — kicks off the async Transaction Status query; the
     *       voucher is issued and texted when Safaricom's result arrives.</li>
     *   <li>already claimed and the pass still has time — re-sends the code to
     *       the payer and reports the minutes remaining (a reconnect).</li>
     *   <li>already claimed but the pass is used up — says so.</li>
     * </ul>
     * The unique receipt column is what makes a code claim-once: the same code
     * can never mint a second voucher.
     */
    @Transactional
    public ClaimOutcome verifyByCode(String rawCode) {
        String receipt = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (!gatewayService.transactionStatusAvailable()) {
            return ClaimOutcome.of(ClaimResult.UNAVAILABLE);
        }
        ManualClaim existing = manualClaims.findByReceipt(receipt).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == ManualClaim.Status.RESOLVED) {
                return reconnect(existing);
            }
            if (existing.getStatus() == ManualClaim.Status.PENDING) {
                return ClaimOutcome.of(ClaimResult.CHECKING); // a query is already in flight
            }
            // A previous attempt failed — let them try again with a fresh query.
            existing.setStatus(ManualClaim.Status.PENDING);
            existing.setMessage(null);
            fireStatusQuery(existing);
            return ClaimOutcome.of(existing.getConversationId() != null
                    ? ClaimResult.CHECKING : ClaimResult.UNAVAILABLE);
        }
        ManualClaim claim = manualClaims.save(ManualClaim.builder()
                .receipt(receipt)
                .status(ManualClaim.Status.PENDING)
                .build());
        fireStatusQuery(claim);
        return ClaimOutcome.of(claim.getConversationId() != null
                ? ClaimResult.CHECKING : ClaimResult.UNAVAILABLE);
    }

    /**
     * The code has already been verified once. If its voucher still has time,
     * re-send it to the payer and report the minutes left; otherwise it's used
     * up. The voucher only ever goes back to the number that paid, so re-pasting
     * someone else's code can't hand their remaining time to a stranger.
     */
    private ClaimOutcome reconnect(ManualClaim claim) {
        Voucher voucher = claim.getVoucherId() == null ? null
                : voucherRepository.findById(claim.getVoucherId()).orElse(null);
        if (voucher == null || voucher.getStatus() == Voucher.Status.EXPIRED || voucher.isExhausted()) {
            return ClaimOutcome.of(ClaimResult.EXPIRED);
        }
        if (claim.getPhoneNumber() != null && !claim.getPhoneNumber().isBlank()) {
            notificationService.send(
                    NotificationTemplate.Key.VOUCHER_ISSUED, claim.getPhoneNumber(),
                    Map.of("business", portalSettingsService.settings().getBusinessName(), "code", voucher.getCode()));
        }
        int remaining = (int) Math.ceil(voucher.getRemainingSeconds() / 60.0);
        return new ClaimOutcome(ClaimResult.ALREADY_ACTIVE, remaining);
    }

    private void fireStatusQuery(ManualClaim claim) {
        String conversationId = mpesaService.queryTransactionStatus(claim.getReceipt());
        if (conversationId == null || conversationId.isBlank()) {
            claim.setStatus(ManualClaim.Status.FAILED);
            claim.setMessage("Could not reach M-Pesa to verify the code");
            claim.setResolvedAt(Instant.now());
        } else {
            claim.setConversationId(conversationId);
        }
        manualClaims.save(claim);
    }

    /**
     * Handles the async Transaction Status result. Confirms the code is a real,
     * completed payment made to this business for an amount that matches a
     * plan, then issues the voucher and texts it — otherwise records why it was
     * refused. Idempotent: a claim is only ever resolved once.
     */
    @Transactional
    public void handleTransactionResult(JsonNode body) {
        JsonNode result = body.path("Result");
        String conversationId = result.path("ConversationID").asText();
        String receipt = result.path("TransactionID").asText();

        ManualClaim claim = null;
        if (conversationId != null && !conversationId.isBlank()) {
            claim = manualClaims.findByConversationId(conversationId).orElse(null);
        }
        if (claim == null && receipt != null && !receipt.isBlank()) {
            claim = manualClaims.findByReceipt(receipt.trim().toUpperCase()).orElse(null);
        }
        if (claim == null) {
            log.warn("Transaction result for unknown claim (conversation {}, receipt {})", conversationId, receipt);
            return;
        }
        if (claim.getStatus() != ManualClaim.Status.PENDING) {
            return; // already resolved or failed — never issue twice
        }

        if (result.path("ResultCode").asInt(-1) != 0) {
            failClaim(claim, "M-Pesa has no completed payment for that code");
            return;
        }

        Map<String, String> params = new HashMap<>();
        for (JsonNode p : result.path("ResultParameters").path("ResultParameter")) {
            params.put(p.path("Key").asText(), p.path("Value").asText());
        }

        BigDecimal amount = parseAmount(params);
        if (amount == null) {
            failClaim(claim, "Could not read the amount from M-Pesa");
            return;
        }
        // Confirm the money actually came to this business, not somewhere else.
        String creditParty = params.getOrDefault("CreditPartyName", "");
        String shortCode = gatewayService.daraja().shortCode();
        if (shortCode != null && !shortCode.isBlank() && !creditParty.contains(shortCode)) {
            failClaim(claim, "That payment wasn't made to this business");
            return;
        }

        Plan plan = matchPlanByAmount(amount);
        if (plan == null) {
            failClaim(claim, "The amount paid doesn't match any plan — contact support");
            return;
        }

        // The voucher goes to whoever Safaricom says actually paid, so it never
        // lands on a number a stranger typed in.
        String payerPhone = parsePayerPhone(params.get("DebitPartyName"));
        if (payerPhone == null) {
            failClaim(claim, "Could not read the paying number from M-Pesa — contact support");
            return;
        }
        claim.setPhoneNumber(payerPhone);

        // The money for this one came in as a claimed M-Pesa code rather than a
        // Payment row, so stamp where it came from — otherwise the revenue
        // audit sees a voucher with nothing behind it.
        Voucher voucher = voucherService.issue(plan, payerPhone, null, null, "mpesa-claim");
        claim.setPlanId(plan.getId());
        claim.setVoucherId(voucher.getId());
        claim.setStatus(ManualClaim.Status.RESOLVED);
        claim.setResolvedAt(Instant.now());
        claim.setMessage(null);
        manualClaims.save(claim);
        log.info("Manual claim {} resolved, voucher {} issued for {}", claim.getId(), voucher.getCode(), plan.getName());

        notificationService.send(
                NotificationTemplate.Key.VOUCHER_ISSUED, claim.getPhoneNumber(),
                Map.of("business", portalSettingsService.settings().getBusinessName(), "code", voucher.getCode()));
        webhookService.dispatch("voucher.generated", Map.of(
                "code", voucher.getCode(),
                "plan", plan.getName(),
                "phone", claim.getPhoneNumber()));
        loyaltyService.earn(claim.getPhoneNumber(), amount);
    }

    private void failClaim(ManualClaim claim, String reason) {
        claim.setStatus(ManualClaim.Status.FAILED);
        claim.setMessage(reason);
        claim.setResolvedAt(Instant.now());
        manualClaims.save(claim);
        log.info("Manual claim {} refused: {}", claim.getId(), reason);
    }

    /** M-Pesa spells the amount differently across products; try the usual keys. */
    private static BigDecimal parseAmount(Map<String, String> params) {
        for (String key : List.of("Amount", "TransactionAmount")) {
            String v = params.get(key);
            if (v != null && !v.isBlank()) {
                try {
                    return new BigDecimal(v.trim());
                } catch (NumberFormatException ignored) {
                    // try the next key
                }
            }
        }
        return null;
    }

    /**
     * Pulls the payer's MSISDN out of M-Pesa's "DebitPartyName", which reads
     * like "254712345678 - John Doe". Returns a 2547/2541 number or null.
     */
    static String parsePayerPhone(String debitPartyName) {
        if (debitPartyName == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : debitPartyName.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break; // the number is the leading run of digits
            }
        }
        String n = digits.toString();
        return n.matches("254\\d{9}") ? n : null;
    }

    /** The single active hotspot plan whose price is exactly the amount paid. */
    private Plan matchPlanByAmount(BigDecimal amount) {
        return planRepository.findAll().stream()
                .filter(Plan::isActive)
                .filter(p -> p.getEffectiveType() == Plan.Type.HOTSPOT)
                .filter(p -> p.getPrice() != null && p.getPrice().compareTo(amount) == 0)
                .findFirst()
                .orElse(null);
    }
}
