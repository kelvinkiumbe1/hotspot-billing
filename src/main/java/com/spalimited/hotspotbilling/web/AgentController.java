package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Agent;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.domain.VoucherBatch;
import com.spalimited.hotspotbilling.service.AgentService;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Reseller agents, their voucher batches and derived sales figures. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AuditService audit;

    // --- Agents ---

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return agentService.agentScoreboard();
    }

    public record AgentRequest(
            @NotBlank String fullName,
            @Pattern(regexp = "254\\d{9}", message = "Phone must be in 2547XXXXXXXX format") String phoneNumber,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{2,10}", message = "Code must be 2-10 letters or digits") String code,
            @Min(0) @Max(60) int commissionPercent,
            String location) {
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public Agent createAgent(@Valid @RequestBody AgentRequest request, Principal principal) {
        Agent agent = agentService.createAgent(request.fullName(), request.phoneNumber(),
                request.code(), request.commissionPercent(), request.location());
        audit.record(principal, "agent.create", "Added agent " + agent.getFullName() + " (" + agent.getCode() + ")");
        return agent;
    }

    @PatchMapping("/agents/{id}/toggle")
    public Agent toggleAgent(@PathVariable Long id, Principal principal) {
        Agent agent = agentService.toggleAgent(id);
        audit.record(principal, "agent.toggle",
                agent.getFullName() + " is now " + (agent.isActive() ? "active" : "inactive"));
        return agent;
    }

    public record PayoutRequest(@NotNull @Min(1) BigDecimal amount) {
    }

    @PostMapping("/agents/{id}/commission")
    public Agent payCommission(@PathVariable Long id, @Valid @RequestBody PayoutRequest request, Principal principal) {
        Agent agent = agentService.recordCommissionPayout(id, request.amount());
        audit.record(principal, "agent.commission",
                "Paid KES " + request.amount() + " commission to " + agent.getFullName());
        return agent;
    }

    @DeleteMapping("/agents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgent(@PathVariable Long id, Principal principal) {
        audit.record(principal, "agent.delete", "Removed agent " + id);
        agentService.deleteAgent(id);
    }

    // --- Batches ---

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() {
        return agentService.batchList();
    }

    public record BatchRequest(
            Long planId,
            @Min(1) @Max(44640) Integer customMinutes,
            @Min(1) @Max(500) int count,
            String prefix,
            @Min(6) @Max(16) Integer codeLength,
            Long agentId,
            String note) {
    }

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherBatch createBatch(@Valid @RequestBody BatchRequest request, Principal principal) {
        VoucherBatch batch = agentService.createBatch(request.planId(), request.customMinutes(),
                request.count(), request.prefix(), request.codeLength(), request.agentId(),
                request.note(), principal.getName());
        audit.record(principal, "batch.create",
                "Generated " + batch.getReference() + " with " + request.count() + " voucher(s)");
        return batch;
    }

    public record AssignRequest(Long agentId) {
    }

    @PatchMapping("/batches/{id}/assign")
    public VoucherBatch assignBatch(@PathVariable Long id, @RequestBody AssignRequest request, Principal principal) {
        VoucherBatch batch = agentService.assignBatch(id, request.agentId());
        audit.record(principal, "batch.assign", batch.getReference()
                + (request.agentId() == null ? " returned to head office" : " assigned to agent " + request.agentId()));
        return batch;
    }

    @GetMapping("/batches/{id}/vouchers")
    public List<Voucher> batchVouchers(@PathVariable Long id) {
        return agentService.vouchersInBatch(id);
    }
}
