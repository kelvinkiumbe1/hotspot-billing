package com.spalimited.hotspotbilling.service;

import tools.jackson.databind.JsonNode;
import com.spalimited.hotspotbilling.domain.CustomPlanSettings;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

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
    private final CustomPlanService customPlanService;
    private final PromotionService promotionService;
    private final NotificationService notificationService;
    private final PortalSettingsService portalSettingsService;
    private final SubscriptionService subscriptionService;

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
        String checkoutRequestId = mpesaService.stkPush(phoneNumber, price, "HOTSPOT-" + planId);
        Payment payment = Payment.builder()
                .phoneNumber(phoneNumber)
                .amount(price)
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

        payment.setCompletedAt(Instant.now());
        if (resultCode != 0) {
            payment.setStatus(Payment.Status.FAILED);
            log.info("Payment {} failed: {}", payment.getId(), stkCallback.path("ResultDesc").asText());
            return;
        }

        // Confirm the money that arrived matches what we asked for. With STK
        // the amount is server-set so a genuine callback always matches; a
        // mismatch means either a forged callback that slipped the source
        // check or an integration fault, and either way must not mint a
        // voucher. Recorded as FAILED so it surfaces in reconciliation.
        java.math.BigDecimal expected = payment.getAmount();
        if (expected != null && (paidAmount == null || paidAmount.compareTo(expected) != 0)) {
            payment.setStatus(Payment.Status.FAILED);
            log.warn("Payment {} rejected: expected {} but callback reported {}",
                    payment.getId(), expected, paidAmount);
            return;
        }

        payment.setMpesaReceiptNumber(receiptNumber);
        payment.setStatus(Payment.Status.SUCCESS);
        Voucher voucher = payment.getCustomMinutes() != null
                ? voucherService.issueCustom(payment.getPlan(), payment.getPhoneNumber(), payment.getCustomMinutes())
                : voucherService.issue(payment.getPlan(), payment.getPhoneNumber());
        payment.setVoucher(voucher);
        log.info("Payment {} succeeded, voucher {} issued", payment.getId(), voucher.getCode());
        notificationService.send(
                com.spalimited.hotspotbilling.domain.NotificationTemplate.Key.VOUCHER_ISSUED,
                payment.getPhoneNumber(),
                java.util.Map.of(
                        "business", portalSettingsService.settings().getBusinessName(),
                        "code", voucher.getCode()));
    }
}
