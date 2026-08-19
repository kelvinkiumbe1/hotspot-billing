package com.spalimited.hotspotbilling.web;

import tools.jackson.databind.JsonNode;
import com.spalimited.hotspotbilling.config.MpesaCallbackGuard;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.AgentPayoutService;
import com.spalimited.hotspotbilling.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final AgentPayoutService agentPayouts;
    private final MpesaCallbackGuard callbackGuard;
    private final com.spalimited.hotspotbilling.service.i18n.Messages messages;
    private final com.spalimited.hotspotbilling.service.payments.PaymentProviders providers;
    private final com.spalimited.hotspotbilling.service.PaymentGatewayService gatewayService;

    /**
     * The language for this reply.
     *
     * <p>Taken from the browser, then filtered through the operator's setting —
     * some deployments want one language on everything, and quietly ignoring
     * that would be its own bug.
     */
    private com.spalimited.hotspotbilling.service.i18n.Language languageOf(String acceptLanguage) {
        return messages.forCustomer(com.spalimited.hotspotbilling.service.i18n.Language
                .fromAcceptHeader(acceptLanguage).code());
    }

    public record StkPushRequest(
            @com.spalimited.hotspotbilling.config.Phone
            String phoneNumber,
            @NotNull Long planId,
            /**
             * Which wallet the customer picked, or null to use the first one the
             * operator offers. Null is the ordinary case for a market with a
             * single wallet, and for USSD, which cannot show a picker.
             */
            String method) {
    }

    /** Customer picks a plan on the captive portal and gets an STK prompt. */
    @PostMapping("/stk-push")
    public Map<String, Object> stkPush(@Valid @RequestBody StkPushRequest request,
                                       @RequestHeader(value = "Accept-Language", required = false)
                                       String acceptLanguage) {
        return started(paymentService.initiateStkPush(request.phoneNumber(), request.planId(), request.method()),
                acceptLanguage);
    }

    /**
     * The same answer whichever rail took the payment, because the portal
     * should not have to know which one is configured.
     *
     * <p>A checkout URL means "send them here to pay"; its absence means the
     * customer's handset is already showing a PIN prompt. The wording follows,
     * so nobody is told to check a phone that will never buzz.
     */
    private Map<String, Object> started(Payment payment, String acceptLanguage) {
        var language = languageOf(acceptLanguage);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("paymentId", payment.getId());
        out.put("status", payment.getStatus());
        out.put("provider", payment.getProvider());
        // Named for the rail the customer chose, not the operator's default
        // brand. With several gateways live those are different things, and
        // getting it wrong tells somebody paying by MTN to enter an M-Pesa PIN.
        String rail = labelFor(payment.getProvider());
        if (payment.getCheckoutUrl() != null) {
            out.put("checkoutUrl", payment.getCheckoutUrl());
            out.put("message", messages.forRail(language, "pay.openingCheckout", rail));
        } else {
            out.put("message", messages.forRail(language, "pay.checkPhone", rail));
        }
        return out;
    }

    public record CustomStkPushRequest(
            @com.spalimited.hotspotbilling.config.Phone
            String phoneNumber,
            @NotNull Integer minutes,
            String method) {
    }

    /** Pay-per-minute purchase: the customer typed exactly how long they need. */
    @PostMapping("/stk-push-custom")
    public Map<String, Object> stkPushCustom(@Valid @RequestBody CustomStkPushRequest request,
                                             @RequestHeader(value = "Accept-Language", required = false)
                                             String acceptLanguage) {
        return started(paymentService.initiateCustomStkPush(request.phoneNumber(), request.minutes(), request.method()),
                acceptLanguage);
    }

/**
     * Every way a customer here can pay.
     *
     * <p>Public, because the captive portal reads it before anyone has paid for
     * anything. Nothing secret is in it: the names of the wallets an operator
     * accepts are on their shopfront.
     */
    @GetMapping("/methods")
    public Map<String, Object> methods(
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        var language = languageOf(acceptLanguage);
        java.util.List<Map<String, Object>> automatic = new java.util.ArrayList<>();
        for (var provider : providers.enabled()) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("kind", provider.kind().name());
            row.put("label", label(provider.kind()));
            // Whether the customer ends up on a page or holding their phone.
            // The portal words the next screen differently for each, and getting
            // it wrong tells somebody to watch for a prompt that never comes.
            row.put("prompt", PROMPTS_PHONE.contains(provider.kind()));
            automatic.add(row);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("methods", automatic);
        out.put("manual", gatewayService.allManualInstructions());
        out.put("message", automatic.isEmpty()
                ? messages.get(language, "pay.noGateway") : null);
        return out;
    }

    /**
     * The rails that prompt the handset rather than opening a page.
     *
     * <p>Listed rather than inferred, because it is a property of how each
     * provider actually behaves and not of anything in its configuration.
     */
    private static final java.util.Set<PaymentGateway.Kind> PROMPTS_PHONE = java.util.Set.of(
            PaymentGateway.Kind.MPESA_API,
            PaymentGateway.Kind.VODACOM_MPESA,
            PaymentGateway.Kind.WAAFIPAY,
            PaymentGateway.Kind.MTN_MOMO,
            PaymentGateway.Kind.AIRTEL_MONEY,
            PaymentGateway.Kind.PAYNOW);

    /** What a customer should see this wallet called. */
    /** The customer-facing name of a rail stored as text, or null if unreadable. */
    private static String labelFor(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return label(PaymentGateway.Kind.valueOf(kind.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String label(PaymentGateway.Kind kind) {
        return switch (kind) {
            case MPESA_API, VODACOM_MPESA -> "M-Pesa";
            case MTN_MOMO -> "MTN MoMo";
            case AIRTEL_MONEY -> "Airtel Money";
            case PAYNOW -> "EcoCash";
            case PAYSTACK -> "Card or bank";
            case FLUTTERWAVE -> "Card or mobile money";
            case STRIPE -> "Card";
            case CHAPA -> "telebirr or card";
            // Named for the wallet most Egyptians would look for, with the card
            // beside it, because "Paymob" means nothing to a customer.
            case PAYMOB -> "Vodafone Cash or card";
            case KONNECT -> "Wallet or card";
            case WAAFIPAY -> "EVC Plus";
            case ORANGE_MONEY -> "Orange Money";
            case WAVE -> "Wave";
            default -> kind.name();
        };
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

    public record RecoverRequest(
            @com.spalimited.hotspotbilling.config.Phone
            String phoneNumber) {
    }

    public record VerifyCodeRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9]{6,20}", message = "Enter the M-Pesa confirmation code")
            String code) {
    }

    /**
     * "Here's my M-Pesa code (or the whole confirmation SMS)." Verifies it
     * against Safaricom (Transaction Status). A new code is checked and the
     * voucher texted to the number that paid; a code already claimed reconnects
     * the customer to whatever time is left, or says the pass is used up.
     */
    @PostMapping("/verify-code")
    public Map<String, Object> verifyCode(@Valid @RequestBody VerifyCodeRequest request,
                                          @RequestHeader(value = "Accept-Language", required = false)
                                          String acceptLanguage) {
        PaymentService.ClaimOutcome outcome = paymentService.verifyByCode(request.code());
        Integer left = outcome.remainingMinutes();
        var language = languageOf(acceptLanguage);
        String message = switch (outcome.result()) {
            case CHECKING -> messages.get(language, "verify.checking");
            // The minutes are appended rather than woven into the sentence: a
            // number reads the same in all four languages, and an absent one
            // must not leave a dangling dash mid-sentence.
            case ALREADY_ACTIVE -> messages.get(language, "verify.stillActive")
                    + (left != null ? " (" + left + " min)" : "");
            case EXPIRED -> messages.get(language, "verify.usedUp");
            case UNAVAILABLE -> messages.get(language, "verify.unavailable");
        };
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("result", outcome.result().name());
        out.put("message", message);
        out.put("remainingMinutes", left);
        return out;
    }

    /** Async Transaction Status result posted by Safaricom Daraja. */
    @PostMapping("/mpesa/transaction-result")
    public Map<String, Object> transactionResult(@RequestBody JsonNode body, HttpServletRequest request) {
        callbackGuard.assertFromSafaricom(request);
        log.debug("Daraja transaction-status result: {}", body);
        paymentService.handleTransactionResult(body);
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    /**
     * Async B2C result posted by Daraja — the moment an agent's commission
     * actually counts as paid. Always answered with an accept, because a
     * non-2xx makes Safaricom retry, and a retry we then process twice would
     * credit the agent twice.
     */
    @PostMapping("/mpesa/b2c-result")
    public Map<String, Object> b2cResult(@RequestBody JsonNode body, HttpServletRequest request) {
        callbackGuard.assertFromSafaricom(request);
        log.debug("Daraja B2C result: {}", body);
        try {
            agentPayouts.handleB2cResult(body);
        } catch (Exception e) {
            log.warn("Could not apply B2C result: {}", e.getMessage());
        }
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    /** Timeout notice for a payout Safaricom couldn't complete in time. */
    @PostMapping("/mpesa/b2c-timeout")
    public Map<String, Object> b2cTimeout(@RequestBody JsonNode body, HttpServletRequest request) {
        callbackGuard.assertFromSafaricom(request);
        // Deliberately not marked failed: a timeout is Safaricom saying it does
        // not know yet, and treating that as "unpaid" invites a double payment.
        log.warn("Daraja B2C payout timed out: {}", body);
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    /** Timeout notice for a status query Safaricom couldn't answer in time. */
    @PostMapping("/mpesa/transaction-timeout")
    public Map<String, Object> transactionTimeout(@RequestBody JsonNode body, HttpServletRequest request) {
        callbackGuard.assertFromSafaricom(request);
        log.warn("Daraja transaction-status timed out: {}", body);
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    /**
     * "I paid but wasn't connected." Settles a pending payment on demand and
     * re-sends the voucher — always by SMS to the number that paid, so the code
     * is never returned here and can't be recovered by anyone else.
     */
    @PostMapping("/recover")
    public Map<String, Object> recover(@Valid @RequestBody RecoverRequest request,
                                       @RequestHeader(value = "Accept-Language", required = false)
                                       String acceptLanguage) {
        PaymentService.RecoveryResult result = paymentService.recoverByPhone(request.phoneNumber());
        var language = languageOf(acceptLanguage);
        String message = switch (result) {
            case SENT -> messages.get(language, "recover.sent");
            case STILL_PENDING -> messages.get(language, "recover.pending");
            case NO_SMS -> messages.get(language, "recover.noSms");
            case FAILED -> messages.get(language, "recover.failed");
            case NONE -> messages.get(language, "recover.none");
        };
        return Map.of("result", result.name(), "message", message);
    }
}
