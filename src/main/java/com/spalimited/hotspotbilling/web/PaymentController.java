package com.spalimited.hotspotbilling.web;

import tools.jackson.databind.JsonNode;
import com.spalimited.hotspotbilling.config.MpesaCallbackGuard;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final MpesaCallbackGuard callbackGuard;

    public record StkPushRequest(
            @Pattern(regexp = "254\\d{9}", message = "Phone must be in 2547XXXXXXXX format")
            String phoneNumber,
            @NotNull Long planId) {
    }

    /** Customer picks a plan on the captive portal and gets an STK prompt. */
    @PostMapping("/stk-push")
    public Map<String, Object> stkPush(@Valid @RequestBody StkPushRequest request) {
        Payment payment = paymentService.initiateStkPush(request.phoneNumber(), request.planId());
        return Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus(),
                "message", "Check your phone and enter your M-Pesa PIN");
    }

    public record CustomStkPushRequest(
            @Pattern(regexp = "254\\d{9}", message = "Phone must be in 2547XXXXXXXX format")
            String phoneNumber,
            @NotNull Integer minutes) {
    }

    /** Pay-per-minute purchase: the customer typed exactly how long they need. */
    @PostMapping("/stk-push-custom")
    public Map<String, Object> stkPushCustom(@Valid @RequestBody CustomStkPushRequest request) {
        Payment payment = paymentService.initiateCustomStkPush(request.phoneNumber(), request.minutes());
        return Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus(),
                "message", "Check your phone and enter your M-Pesa PIN");
    }

    /** Poll endpoint for the portal to learn when the voucher is ready. */
    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable Long id) {
        Payment payment = paymentService.get(id);
        return Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus(),
                "voucherCode", payment.getVoucher() != null ? payment.getVoucher().getCode() : "");
    }

    /** Async result posted by Safaricom Daraja. */
    @PostMapping("/mpesa/callback")
    public Map<String, Object> mpesaCallback(@RequestBody JsonNode body, HttpServletRequest request) {
        callbackGuard.assertFromSafaricom(request);
        log.debug("Daraja callback: {}", body);
        paymentService.handleStkCallback(body);
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }
}
