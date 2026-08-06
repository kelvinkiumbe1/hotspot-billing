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

    public record GenerateRequest(@NotNull Long planId, @Min(1) @Max(20) int count,
                                  String prefix, Integer codeLength) {
    }

    @GetMapping("/vouchers")
    public List<Voucher> vouchers() {
        return voucherRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @PostMapping("/vouchers/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Voucher> generate(@Valid @RequestBody GenerateRequest request) {
        Plan plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + request.planId()));
        return IntStream.range(0, request.count())
                .mapToObj(i -> voucherService.issue(plan, null, request.prefix(), request.codeLength()))
                .toList();
    }

    // --- Router connectivity check ---

    @PostMapping("/ping")
    public Map<String, Object> ping() {
        mikrotikService.testConnection(mikrotikService.settings());
        return Map.of("success", true, "message", "Router reachable — connected and logged in");
    }
}
