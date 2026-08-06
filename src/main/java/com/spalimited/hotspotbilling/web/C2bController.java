package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.C2bPayment;
import com.spalimited.hotspotbilling.service.C2bService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * PayBill (C2B) endpoints. Safaricom posts to the two public callbacks;
 * the admin views and reconciles under /api/admin/c2b.
 *
 * Register these with Daraja C2B "Register URL":
 *   ValidationURL   = {PUBLIC_URL}/api/payments/mpesa/c2b/validation
 *   ConfirmationURL = {PUBLIC_URL}/api/payments/mpesa/c2b/confirmation
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class C2bController {

    private final C2bService c2bService;

    /** Safaricom asks whether to accept the payment; we accept everything. */
    @PostMapping("/api/payments/mpesa/c2b/validation")
    public Map<String, Object> validation(@RequestBody JsonNode body) {
        log.debug("C2B validation: {}", body);
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    /** Safaricom confirms money has landed — this is where we credit. */
    @PostMapping("/api/payments/mpesa/c2b/confirmation")
    public Map<String, Object> confirmation(@RequestBody JsonNode body) {
        log.debug("C2B confirmation: {}", body);
        try {
            String transactionId = text(body, "TransID");
            BigDecimal amount = new BigDecimal(text(body, "TransAmount"));
            String phone = text(body, "MSISDN");
            String billRef = text(body, "BillRefNumber");
            String name = (text(body, "FirstName") + " " + text(body, "MiddleName") + " "
                    + text(body, "LastName")).trim().replaceAll("\\s+", " ");
            c2bService.handleConfirmation(transactionId, amount, phone, billRef, name);
        } catch (Exception e) {
            // Always ACK: Safaricom retries otherwise, and the money has already moved.
            log.error("Could not process C2B confirmation: {}", e.getMessage(), e);
        }
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }

    private String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        return node.isMissingNode() || node.isNull() ? "" : node.asText();
    }

    // --- Admin reconciliation ---

    @PreAuthorize("hasAuthority('FINANCE')")
    @GetMapping("/api/admin/c2b")
    public List<C2bPayment> all() {
        return c2bService.recent();
    }

    @PreAuthorize("hasAuthority('FINANCE')")
    @GetMapping("/api/admin/c2b/unmatched")
    public List<C2bPayment> unmatched() {
        return c2bService.unmatched();
    }

    public record ApplyRequest(@NotNull Long subscriberId, @Min(1) @Max(24) int months) {
    }

    @PreAuthorize("hasAuthority('FINANCE')")
    @PostMapping("/api/admin/c2b/{id}/apply")
    public C2bPayment apply(@PathVariable Long id, @Valid @RequestBody ApplyRequest request, Principal principal) {
        return c2bService.applyManually(id, request.subscriberId(), request.months(), principal.getName());
    }
}
