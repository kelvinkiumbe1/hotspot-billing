package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import com.spalimited.hotspotbilling.service.VoucherService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Admin-only operations. Everything under /api/admin requires the ADMIN
 * role (HTTP Basic) — see SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PlanRepository planRepository;
    private final VoucherRepository voucherRepository;
    private final PaymentRepository paymentRepository;
    private final VoucherService voucherService;
    private final com.spalimited.hotspotbilling.service.MikrotikService mikrotikService;

    // --- Dashboard ---

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "totalRevenue", paymentRepository.totalAmountByStatus(Payment.Status.SUCCESS),
                "successfulPayments", paymentRepository.countByStatus(Payment.Status.SUCCESS),
                "pendingPayments", paymentRepository.countByStatus(Payment.Status.PENDING),
                "failedPayments", paymentRepository.countByStatus(Payment.Status.FAILED),
                "unusedVouchers", voucherRepository.countByStatus(Voucher.Status.UNUSED),
                "activeVouchers", voucherRepository.countByStatus(Voucher.Status.ACTIVE),
                "activePlans", planRepository.findByActiveTrueOrderByPriceAsc().size());
    }

    @GetMapping("/payments")
    public List<Payment> payments() {
        return paymentRepository.findTop100ByOrderByCreatedAtDesc();
    }

    // --- Plans ---

    public record PlanRequest(
            @NotBlank String name,
            @NotNull @Min(1) BigDecimal price,
            @Min(1) int durationMinutes,
            Integer dataLimitMb,
            String bandwidth,
            String mikrotikProfile,
            @Min(1) @Max(10) Integer maxDevices) {
    }

    @GetMapping("/plans")
    public List<Plan> allPlans() {
        return planRepository.findAll();
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Plan createPlan(@Valid @RequestBody PlanRequest request) {
        Plan plan = Plan.builder()
                .name(request.name())
                .price(request.price())
                .durationMinutes(request.durationMinutes())
                .dataLimitMb(request.dataLimitMb())
                .bandwidth(request.bandwidth())
                .mikrotikProfile(request.mikrotikProfile())
                .maxDevices(request.maxDevices())
                .build();
        return planRepository.save(plan);
    }

    @PatchMapping("/plans/{id}/toggle")
    public Plan togglePlan(@PathVariable Long id) {
        Plan plan = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + id));
        plan.setActive(!plan.isActive());
        return planRepository.save(plan);
    }

    // --- Vouchers ---

    public record GenerateRequest(@NotNull Long planId, @Min(1) @Max(500) int count,
                                  String prefix, Integer codeLength) {
    }

    @GetMapping("/vouchers")
    public List<Voucher> vouchers() {
        return voucherRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @PostMapping("/vouchers/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Voucher> generateVouchers(@Valid @RequestBody GenerateRequest request) {
        Plan plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + request.planId()));
        return IntStream.range(0, request.count())
                .mapToObj(i -> voucherService.issue(plan, null, request.prefix(), request.codeLength()))
                .toList();
    }

    /**
     * Disables an active voucher: kicks the device off the router, removes
     * the hotspot user and marks the voucher expired.
     */
    @PatchMapping("/vouchers/{id}/revoke")
    public Voucher revokeVoucher(@PathVariable Long id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown voucher: " + id));
        if (voucher.getStatus() != Voucher.Status.ACTIVE) {
            throw new IllegalStateException("Only active vouchers can be disabled");
        }
        mikrotikService.removeVoucher(voucher);
        voucher.setStatus(Voucher.Status.EXPIRED);
        voucher.setExpiresAt(java.time.Instant.now());
        return voucherRepository.save(voucher);
    }

    /** Clears a voucher's MAC lock so the customer can switch devices. */
    @PatchMapping("/vouchers/{id}/unbind")
    public Voucher unbindVoucher(@PathVariable Long id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown voucher: " + id));
        mikrotikService.unbindVoucher(voucher);
        return voucherRepository.save(voucher);
    }

    /** Removes a voucher that was never sold or used (e.g. a bad batch). */
    @DeleteMapping("/vouchers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVoucher(@PathVariable Long id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown voucher: " + id));
        if (voucher.getStatus() != Voucher.Status.UNUSED) {
            throw new IllegalStateException("Only unused vouchers can be deleted");
        }
        mikrotikService.removeVoucher(voucher);
        voucherRepository.delete(voucher);
    }
}
