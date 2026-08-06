package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.PayoutRequest;
import com.spalimited.hotspotbilling.repository.PayoutRequestRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;

/**
 * Technician payout requests. Technicians create and view their own
 * requests under /api/tech; admins list all of them and settle or reject
 * under /api/admin (both HTTP Basic — see SecurityConfig).
 */
@RestController
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutRequestRepository payouts;

    // --- Technician side ---

    public record PayoutRequestBody(@NotNull @Min(1) BigDecimal amount, String note) {
    }

    @GetMapping("/api/tech/payouts")
    public List<PayoutRequest> mine(Principal principal) {
        return payouts.findByTechnicianOrderByCreatedAtDesc(principal.getName());
    }

    @PostMapping("/api/tech/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    public PayoutRequest create(@Valid @RequestBody PayoutRequestBody body, Principal principal) {
        return payouts.save(PayoutRequest.builder()
                .technician(principal.getName())
                .amount(body.amount())
                .note(body.note())
                .build());
    }

    // --- Admin side ---

    @GetMapping("/api/admin/payouts")
    public List<PayoutRequest> all() {
        return payouts.findAllByOrderByCreatedAtDesc();
    }

    public record StatusBody(@NotNull PayoutRequest.Status status) {
    }

    @PatchMapping("/api/admin/payouts/{id}/status")
    public PayoutRequest setStatus(@PathVariable Long id, @Valid @RequestBody StatusBody body) {
        PayoutRequest payout = payouts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payout request: " + id));
        payout.setStatus(body.status());
        return payouts.save(payout);
    }
}
