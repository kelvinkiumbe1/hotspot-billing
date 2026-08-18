package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.service.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Public self-service payment for PPPoE subscribers: look up your account
 * by phone number, get an STK prompt, pay, get reconnected. Works over
 * mobile data even when the home connection is suspended; the M-Pesa PIN
 * on the registered number is the authorization.
 */
@RestController
@RequestMapping("/api/pppoe")
@RequiredArgsConstructor
public class PppoeSelfServiceController {

    private final SubscriptionService subscriptionService;
    private final com.spalimited.hotspotbilling.service.i18n.Messages messages;

    public record LookupRequest(
            @com.spalimited.hotspotbilling.config.Phone
            String phoneNumber) {
    }

    @PostMapping("/lookup")
    public List<Map<String, Object>> lookup(@Valid @RequestBody LookupRequest request) {
        return subscriptionService.findByPhone(request.phoneNumber()).stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "fullName", s.getFullName(),
                        "pppoeUsername", s.getPppoeUsername(),
                        "monthlyFee", s.getMonthlyFee(),
                        "paidUntil", s.getPaidUntil(),
                        "status", s.getStatus()))
                .toList();
    }

    public record PayRequest(
            @NotNull Long subscriberId,
            @com.spalimited.hotspotbilling.config.Phone String phoneNumber,
            @Min(1) @Max(12) int months) {
    }

    @PostMapping("/pay")
    public Map<String, Object> pay(@Valid @RequestBody PayRequest request) {
        SubscriptionPayment payment = subscriptionService.selfPay(
                request.subscriberId(), request.phoneNumber(), request.months());
        return Map.of(
                "paymentId", payment.getId(),
                "amount", payment.getAmount(),
                "message", messages.get("pay.checkPhone"));
    }

    /** Poll endpoint so the page can show success and the new expiry date. */
    @GetMapping("/payments/{id}")
    public Map<String, Object> status(@PathVariable Long id) {
        SubscriptionPayment payment = subscriptionService.getPayment(id);
        return Map.of(
                "status", payment.getStatus(),
                "paidUntil", payment.getSubscriber().getPaidUntil());
    }
}
