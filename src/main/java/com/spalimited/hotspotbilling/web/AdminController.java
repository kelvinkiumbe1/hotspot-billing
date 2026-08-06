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
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final com.spalimited.hotspotbilling.service.CustomPlanService customPlanService;

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
            @Min(1) @Max(10) Integer maxDevices,
            Plan.Type type,
            Plan.Availability availability,
            String burstLimit,
            String burstThreshold,
            String burstTime,
            Boolean fupEnabled,
            @Min(1) Integer fupLimitMb,
            Plan.FupAction fupAction,
            String fupRate,
            Boolean scheduleEnabled,
            String scheduleFrom,
            String scheduleTo,
            Set<Long> allowedRouterIds) {
    }

    @GetMapping("/plans")
    public List<Plan> allPlans() {
        return planRepository.findAll();
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Plan createPlan(@Valid @RequestBody PlanRequest request) {
        return planRepository.save(apply(Plan.builder().build(), request));
    }

    @PutMapping("/plans/{id}")
    public Plan updatePlan(@PathVariable Long id, @Valid @RequestBody PlanRequest request) {
        return planRepository.save(apply(plan(id), request));
    }

    /**
     * Copies a request onto a plan, validating the combinations RouterOS
     * cares about. Shared by create and update so the rules cannot drift
     * between the two.
     */
    private Plan apply(Plan plan, PlanRequest request) {
        int burstParts = 0;
        if (notBlank(request.burstLimit())) burstParts++;
        if (notBlank(request.burstThreshold())) burstParts++;
        if (notBlank(request.burstTime())) burstParts++;
        if (burstParts != 0 && burstParts != 3) {
            throw new IllegalArgumentException(
                    "Burst needs all three of limit, threshold and time — or leave all three blank");
        }
        if (Boolean.TRUE.equals(request.fupEnabled())
                && (request.fupLimitMb() == null || request.fupLimitMb() <= 0)) {
            throw new IllegalArgumentException("Set a monthly data limit to enforce fair use");
        }
        if (Boolean.TRUE.equals(request.fupEnabled())
                && request.fupAction() == Plan.FupAction.THROTTLE && !notBlank(request.fupRate())) {
            throw new IllegalArgumentException("Throttling needs a reduced rate, e.g. 1M/1M");
        }

        LocalTime from = parseTime(request.scheduleFrom(), "schedule start");
        LocalTime to = parseTime(request.scheduleTo(), "schedule end");
        if (Boolean.TRUE.equals(request.scheduleEnabled()) && (from == null || to == null)) {
            throw new IllegalArgumentException("A schedule needs both a start and an end time");
        }

        Plan.Availability availability = request.availability() != null
                ? request.availability() : Plan.Availability.LIVE;

        plan.setName(request.name());
        plan.setPrice(request.price());
        plan.setDurationMinutes(request.durationMinutes());
        plan.setDataLimitMb(request.dataLimitMb());
        plan.setBandwidth(blankToNull(request.bandwidth()));
        plan.setMikrotikProfile(blankToNull(request.mikrotikProfile()));
        plan.setMaxDevices(request.maxDevices());
        plan.setType(request.type() != null ? request.type() : Plan.Type.HOTSPOT);
        plan.setAvailability(availability);
        // Kept in step so older queries that filter on `active` still behave.
        plan.setActive(availability != Plan.Availability.OFF);
        plan.setBurstLimit(blankToNull(request.burstLimit()));
        plan.setBurstThreshold(blankToNull(request.burstThreshold()));
        plan.setBurstTime(blankToNull(request.burstTime()));
        plan.setFupEnabled(request.fupEnabled());
        plan.setFupLimitMb(request.fupLimitMb());
        plan.setFupAction(request.fupAction());
        plan.setFupRate(blankToNull(request.fupRate()));
        plan.setScheduleEnabled(request.scheduleEnabled());
        plan.setScheduleFrom(from);
        plan.setScheduleTo(to);
        plan.getAllowedRouterIds().clear();
        if (request.allowedRouterIds() != null) {
            plan.getAllowedRouterIds().addAll(request.allowedRouterIds());
        }
        return plan;
    }

    private static LocalTime parseTime(String value, String what) {
        if (!notBlank(value)) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Could not read the " + what + " — use HH:mm");
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }

    /** Cycles live → hidden → off, so one control covers all three states. */
    @PatchMapping("/plans/{id}/toggle")
    public Plan togglePlan(@PathVariable Long id) {
        Plan plan = plan(id);
        Plan.Availability next = switch (plan.getEffectiveAvailability()) {
            case LIVE -> Plan.Availability.HIDDEN;
            case HIDDEN -> Plan.Availability.OFF;
            case OFF -> Plan.Availability.LIVE;
        };
        plan.setAvailability(next);
        plan.setActive(next != Plan.Availability.OFF);
        return planRepository.save(plan);
    }

    public record AvailabilityRequest(@NotNull Plan.Availability availability) {
    }

    @PatchMapping("/plans/{id}/availability")
    public Plan setAvailability(@PathVariable Long id, @Valid @RequestBody AvailabilityRequest request) {
        Plan plan = plan(id);
        plan.setAvailability(request.availability());
        plan.setActive(request.availability() != Plan.Availability.OFF);
        return planRepository.save(plan);
    }

    private Plan plan(Long id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + id));
    }

    // --- Vouchers ---

    public record GenerateRequest(Long planId, @Min(1) @Max(500) int count,
                                  String prefix, Integer codeLength,
                                  @Min(1) @Max(44640) Integer customMinutes) {
    }

    @GetMapping("/vouchers")
    public List<Voucher> vouchers() {
        return voucherRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @PostMapping("/vouchers/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Voucher> generateVouchers(@Valid @RequestBody GenerateRequest request,
                                          java.security.Principal principal) {
        String createdBy = principal.getName();
        if (request.customMinutes() != null) {
            Plan plan = customPlanService.systemPlan(customPlanService.settings());
            return IntStream.range(0, request.count())
                    .mapToObj(i -> voucherService.issueCustom(
                            plan, null, request.customMinutes(), request.prefix(), request.codeLength(), createdBy))
                    .toList();
        }
        if (request.planId() == null) {
            throw new IllegalArgumentException("Choose a plan or a custom duration");
        }
        Plan plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + request.planId()));
        return IntStream.range(0, request.count())
                .mapToObj(i -> voucherService.issue(plan, null, request.prefix(), request.codeLength(), createdBy))
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
