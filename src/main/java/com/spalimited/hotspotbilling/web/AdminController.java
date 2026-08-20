package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.VoucherService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final AuditService auditService;
    private final VoucherRepository voucherRepository;
    private final PaymentRepository paymentRepository;
    private final VoucherService voucherService;
    private final com.spalimited.hotspotbilling.service.MikrotikService mikrotikService;
    private final com.spalimited.hotspotbilling.service.CustomPlanService customPlanService;

    // --- Dashboard ---

    /**
     * Deliberately open to every office role: the overview is the landing
     * page, and a support agent seeing headline counts is harmless. The
     * detail behind each figure is guarded on its own endpoint.
     */
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

    @PreAuthorize("hasAuthority('FINANCE')")
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

    @PreAuthorize("hasAuthority('PRICING')")
    @GetMapping("/plans")
    public List<Plan> allPlans() {
        return planRepository.findAll();
    }

    @PreAuthorize("hasAuthority('PRICING')")
    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public Plan createPlan(@Valid @RequestBody PlanRequest request) {
        return planRepository.save(apply(Plan.builder().build(), request));
    }

    @PreAuthorize("hasAuthority('PRICING')")
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
        // Names are unique in the database; catching it here turns a constraint
        // violation 500 into something the person typing can act on.
        planRepository.findAll().stream()
                .filter(existing -> existing.getName().equalsIgnoreCase(request.name().trim()))
                .filter(existing -> !existing.getId().equals(plan.getId()))
                .findFirst()
                .ifPresent(clash -> {
                    throw new IllegalArgumentException(
                            "A package called \"" + clash.getName() + "\" already exists");
                });

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
    @PreAuthorize("hasAuthority('PRICING')")
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

    @PreAuthorize("hasAuthority('PRICING')")
    @PatchMapping("/plans/{id}/availability")
    public Plan setAvailability(@PathVariable Long id, @Valid @RequestBody AvailabilityRequest request) {
        Plan plan = plan(id);
        plan.setAvailability(request.availability());
        plan.setActive(request.availability() != Plan.Availability.OFF);
        return planRepository.save(plan);
    }

    /**
     * Removes a package that was never sold. Anything with vouchers or
     * payments against it is refused — deleting it would orphan the records
     * that explain past revenue. Withdraw it with availability OFF instead.
     */
    @PreAuthorize("hasAuthority('PRICING')")
    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable Long id) {
        Plan plan = plan(id);
        long vouchers = voucherRepository.findAll().stream()
                .filter(v -> v.getPlan() != null && id.equals(v.getPlan().getId()))
                .count();
        long payments = paymentRepository.findAll().stream()
                .filter(p -> p.getPlan() != null && id.equals(p.getPlan().getId()))
                .count();
        if (vouchers > 0 || payments > 0) {
            throw new IllegalStateException("\"" + plan.getName() + "\" has " + vouchers
                    + " voucher(s) and " + payments + " payment(s) against it. Set it to Off instead,"
                    + " so the history stays intact.");
        }
        planRepository.delete(plan);
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

    @PreAuthorize("hasAuthority('SELL')")
    @GetMapping("/vouchers")
    public List<Voucher> vouchers() {
        return voucherRepository.findTop100ByOrderByCreatedAtDesc();
    }

    /** Headline counts for the voucher page, over the whole stock not just a page of it. */
    @PreAuthorize("hasAuthority('SELL')")
    @GetMapping("/vouchers/summary")
    public Map<String, Object> voucherSummary() {
        List<Voucher> all = voucherRepository.findAll();
        long unused = all.stream().filter(v -> v.getStatus() == Voucher.Status.UNUSED).count();
        long active = all.stream().filter(v -> v.getStatus() == Voucher.Status.ACTIVE).count();
        long expired = all.stream().filter(v -> v.getStatus() == Voucher.Status.EXPIRED).count();
        long redeemed = active + expired; // anything that left the shelf
        long batches = all.stream()
                .map(Voucher::getBatchId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", all.size());
        out.put("unused", unused);
        out.put("active", active);
        out.put("expired", expired);
        out.put("redeemed", redeemed);
        out.put("batches", batches);
        out.put("redemptionPercent", all.isEmpty() ? 0
                : BigDecimal.valueOf(redeemed * 100.0 / all.size())
                        .setScale(1, java.math.RoundingMode.HALF_UP));
        return out;
    }

    /**
     * The whole stock as CSV, for handing to a reseller or keeping a record
     * outside the system. Streamed as a download rather than JSON.
     */
    @PreAuthorize("hasAuthority('SELL')")
    @GetMapping(value = "/vouchers/export", produces = "text/csv")
    public org.springframework.http.ResponseEntity<String> exportVouchers(
            @RequestParam(required = false) String status) {
        StringBuilder csv = new StringBuilder("code,plan,price,duration_minutes,status,created,activated,expires,bound_mac,batch_id,created_by\n");
        voucherRepository.findAll().stream()
                .filter(v -> status == null || status.isBlank()
                        || v.getStatus().name().equalsIgnoreCase(status))
                .sorted(java.util.Comparator.comparing(Voucher::getCreatedAt).reversed())
                .forEach(v -> csv.append(String.join(",",
                        csvCell(v.getCode()),
                        csvCell(v.getPlan() == null ? "" : v.getPlan().getName()),
                        csvCell(v.getPlan() == null ? "" : String.valueOf(v.getPlan().getPrice())),
                        String.valueOf(v.getEffectiveDurationMinutes()),
                        v.getStatus().name(),
                        String.valueOf(v.getCreatedAt()),
                        v.getActivatedAt() == null ? "" : String.valueOf(v.getActivatedAt()),
                        v.getExpiresAt() == null ? "" : String.valueOf(v.getExpiresAt()),
                        csvCell(v.getBoundMac()),
                        v.getBatchId() == null ? "" : String.valueOf(v.getBatchId()),
                        csvCell(v.getCreatedBy()))).append("\n"));

        String filename = "vouchers-" + java.time.LocalDate.now() + ".csv";
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv.toString());
    }

    /** Quotes any cell containing a comma or quote, so the CSV cannot break. */
    private static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public record BulkRequest(@NotNull List<Long> ids) {
    }

    /**
     * Disables many active vouchers at once, and deletes unused ones. Each
     * id is reported separately so one failure does not hide the rest.
     */
    @PreAuthorize("hasAuthority('SELL')")
    @PostMapping("/vouchers/bulk-revoke")
    public Map<String, Object> bulkRevoke(@Valid @RequestBody BulkRequest request) {
        List<String> done = new java.util.ArrayList<>();
        Map<String, String> skipped = new java.util.LinkedHashMap<>();
        for (Long id : request.ids()) {
            Voucher voucher = voucherRepository.findById(id).orElse(null);
            if (voucher == null) {
                skipped.put(String.valueOf(id), "no longer exists");
                continue;
            }
            if (voucher.getStatus() != Voucher.Status.ACTIVE) {
                skipped.put(voucher.getCode(), "not active");
                continue;
            }
            try {
                mikrotikService.removeVoucher(voucher);
            } catch (Exception routerDown) {
                // Still mark it dead here; the router sync will catch up.
                skipped.put(voucher.getCode(), "router unreachable, marked expired anyway");
            }
            voucher.setStatus(Voucher.Status.EXPIRED);
            voucher.setExpiresAt(java.time.Instant.now());
            voucherRepository.save(voucher);
            done.add(voucher.getCode());
        }
        return Map.of("revoked", done, "skipped", skipped);
    }

    @PreAuthorize("hasAuthority('SELL')")
    @PostMapping("/vouchers/bulk-delete")
    public Map<String, Object> bulkDelete(@Valid @RequestBody BulkRequest request) {
        List<String> done = new java.util.ArrayList<>();
        Map<String, String> skipped = new java.util.LinkedHashMap<>();
        for (Long id : request.ids()) {
            Voucher voucher = voucherRepository.findById(id).orElse(null);
            if (voucher == null) {
                skipped.put(String.valueOf(id), "no longer exists");
                continue;
            }
            if (voucher.getStatus() != Voucher.Status.UNUSED) {
                skipped.put(voucher.getCode(), "already used — delete would lose the record");
                continue;
            }
            try {
                mikrotikService.removeVoucher(voucher);
            } catch (Exception routerDown) {
                // The hotspot user may linger; deleting the row is still right.
            }
            voucherRepository.delete(voucher);
            done.add(voucher.getCode());
        }
        return Map.of("deleted", done, "skipped", skipped);
    }

    @PreAuthorize("hasAuthority('SELL')")
    @PostMapping("/vouchers/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Voucher> generateVouchers(@Valid @RequestBody GenerateRequest request,
                                          java.security.Principal principal) {
        String createdBy = principal.getName();
        if (request.customMinutes() != null) {
            Plan plan = customPlanService.systemPlan(customPlanService.settings());
            List<Voucher> issued = IntStream.range(0, request.count())
                    .mapToObj(i -> voucherService.issueCustom(
                            plan, null, request.customMinutes(), request.prefix(), request.codeLength(), createdBy))
                    .toList();
            // Vouchers are stock that can be sold, so issuing them belongs in the
            // trail an owner reads. Each row already carries created_by; that is
            // attribution, not a record anybody reviews.
            auditService.record(createdBy, "voucher.generate",
                    "Issued " + issued.size() + " custom voucher(s) of "
                            + request.customMinutes() + " minutes");
            return issued;
        }
        if (request.planId() == null) {
            throw new IllegalArgumentException("Choose a plan or a custom duration");
        }
        Plan plan = planRepository.findById(request.planId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + request.planId()));
        List<Voucher> issued = IntStream.range(0, request.count())
                .mapToObj(i -> voucherService.issue(plan, null, request.prefix(), request.codeLength(), createdBy))
                .toList();
        auditService.record(createdBy, "voucher.generate",
                "Issued " + issued.size() + " voucher(s) for plan '" + plan.getName() + "'");
        return issued;
    }

    /**
     * Disables an active voucher: kicks the device off the router, removes
     * the hotspot user and marks the voucher expired.
     */
    @PreAuthorize("hasAuthority('SELL')")
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
    @PreAuthorize("hasAuthority('SELL')")
    @PatchMapping("/vouchers/{id}/unbind")
    public Voucher unbindVoucher(@PathVariable Long id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown voucher: " + id));
        mikrotikService.unbindVoucher(voucher);
        return voucherRepository.save(voucher);
    }

    /** Removes a voucher that was never sold or used (e.g. a bad batch). */
    @PreAuthorize("hasAuthority('SELL')")
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
