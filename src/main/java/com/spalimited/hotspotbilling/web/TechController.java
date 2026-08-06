package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.MaintenanceEvent;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.MaintenanceEventRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import com.spalimited.hotspotbilling.service.MikrotikService;
import com.spalimited.hotspotbilling.service.VoucherService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Field technician operations. Everything under /api/tech requires the
 * TECHNICIAN (or ADMIN) role via HTTP Basic — see SecurityConfig.
 * Technicians see maintenance events as their task list, can issue small
 * voucher batches on-site, and can check router connectivity.
 */
@RestController
@RequestMapping("/api/tech")
@RequiredArgsConstructor
public class TechController {

    private final MaintenanceEventRepository maintenanceEvents;
    private final PlanRepository planRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherService voucherService;
    private final MikrotikService mikrotikService;
    private final com.spalimited.hotspotbilling.service.CustomPlanService customPlanService;
    private final com.spalimited.hotspotbilling.repository.TechnicianRepository technicianRepository;
    private final com.spalimited.hotspotbilling.repository.SubscriberRepository subscriberRepository;
    private final com.spalimited.hotspotbilling.service.SubscriptionService subscriptionService;

    // --- Identity & permissions ---

    /** Who am I and what may I do (admins get everything). */
    @GetMapping("/me")
    public Map<String, Object> me(java.security.Principal principal) {
        var tech = technicianRepository.findByUsername(principal.getName()).orElse(null);
        return Map.of(
                "username", principal.getName(),
                "vouchersAllowed", tech == null || tech.isVouchersAllowed(),
                "pppoeAllowed", tech == null || tech.isPppoeAllowed());
    }

    private void requireVoucherPermission(java.security.Principal principal) {
        technicianRepository.findByUsername(principal.getName()).ifPresent(tech -> {
            if (!tech.isVouchersAllowed()) {
                throw new IllegalStateException("Your account is not allowed to issue vouchers — ask the admin");
            }
        });
    }

    private void requirePppoePermission(java.security.Principal principal) {
        var tech = technicianRepository.findByUsername(principal.getName()).orElse(null);
        if (tech != null && !tech.isPppoeAllowed()) {
            throw new IllegalStateException("Your account is not allowed to manage subscribers — ask the admin");
        }
    }

    // --- Tasks (maintenance events) ---

    @GetMapping("/tasks")
    public List<MaintenanceEvent> tasks() {
        return maintenanceEvents.findAllByOrderByScheduledStartAsc();
    }

    @PatchMapping("/tasks/{id}/complete")
    public MaintenanceEvent complete(@PathVariable Long id) {
        MaintenanceEvent event = maintenanceEvents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task: " + id));
        event.setStatus(MaintenanceEvent.Status.COMPLETED);
        return maintenanceEvents.save(event);
    }

    // --- Field voucher generation (small batches only) ---

    public record GenerateRequest(Long planId, @Min(1) @Max(20) int count,
                                  String prefix, Integer codeLength,
                                  @Min(1) @Max(44640) Integer customMinutes) {
    }

    @GetMapping("/vouchers")
    public List<Voucher> vouchers() {
        return voucherRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @PostMapping("/vouchers/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Voucher> generate(@Valid @RequestBody GenerateRequest request,
                                  java.security.Principal principal) {
        requireVoucherPermission(principal);
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

    // --- PPPoE subscribers (permission-gated) ---

    @GetMapping("/subscribers")
    public List<com.spalimited.hotspotbilling.domain.Subscriber> subscribers(java.security.Principal principal) {
        requirePppoePermission(principal);
        return subscriberRepository.findAllByOrderByCreatedAtAsc();
    }

    public record TechSubscriberRequest(
            @jakarta.validation.constraints.NotBlank String fullName,
            @jakarta.validation.constraints.Pattern(regexp = "254\\d{9}") String phoneNumber,
            @jakarta.validation.constraints.NotBlank String pppoeUsername,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 6) String pppoePassword,
            String bandwidth,
            @NotNull @Min(1) java.math.BigDecimal monthlyFee,
            @Min(0) @Max(12) Integer initialMonths,
            String initialMethod) {
    }

    @PostMapping("/subscribers")
    @ResponseStatus(HttpStatus.CREATED)
    public com.spalimited.hotspotbilling.domain.Subscriber createSubscriber(
            @Valid @RequestBody TechSubscriberRequest request, java.security.Principal principal) {
        requirePppoePermission(principal);
        return subscriptionService.create(
                request.fullName(), request.phoneNumber(), request.pppoeUsername(),
                request.pppoePassword(), request.bandwidth(), request.monthlyFee(),
                request.initialMonths() != null ? request.initialMonths() : 1,
                "MPESA".equalsIgnoreCase(request.initialMethod())
                        ? com.spalimited.hotspotbilling.domain.SubscriptionPayment.Method.MPESA
                        : com.spalimited.hotspotbilling.domain.SubscriptionPayment.Method.CASH);
    }

    public record MonthsRequest(@Min(1) @Max(12) int months) {
    }

    @PostMapping("/subscribers/{id}/payments")
    public com.spalimited.hotspotbilling.domain.SubscriptionPayment recordSubscriberPayment(
            @PathVariable Long id, @Valid @RequestBody MonthsRequest request, java.security.Principal principal) {
        requirePppoePermission(principal);
        return subscriptionService.recordCashPayment(id, request.months());
    }

    // --- Router connectivity check ---

    @PostMapping("/ping")
    public Map<String, Object> ping() {
        mikrotikService.testConnection(mikrotikService.settings());
        return Map.of("success", true, "message", "Router reachable — connected and logged in");
    }
}
