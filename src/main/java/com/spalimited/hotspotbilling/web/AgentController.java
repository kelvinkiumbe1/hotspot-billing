package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Agent;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.domain.VoucherBatch;
import com.spalimited.hotspotbilling.domain.AgentPayoutSettings;
import com.spalimited.hotspotbilling.domain.CommissionPayout;
import com.spalimited.hotspotbilling.service.AgentPayoutService;
import com.spalimited.hotspotbilling.service.AgentService;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final AgentPayoutService agentPayouts;
    private final AuditService audit;

    // --- Agents ---

    @PreAuthorize("hasAuthority('SELL')")
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

    @PreAuthorize("hasAuthority('SELL')")
    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public Agent createAgent(@Valid @RequestBody AgentRequest request, Principal principal) {
        Agent agent = agentService.createAgent(request.fullName(), request.phoneNumber(),
                request.code(), request.commissionPercent(), request.location());
        audit.record(principal, "agent.create", "Added agent " + agent.getFullName() + " (" + agent.getCode() + ")");
        return agent;
    }

    @PreAuthorize("hasAuthority('SELL')")
    @PatchMapping("/agents/{id}/toggle")
    public Agent toggleAgent(@PathVariable Long id, Principal principal) {
        Agent agent = agentService.toggleAgent(id);
        audit.record(principal, "agent.toggle",
                agent.getFullName() + " is now " + (agent.isActive() ? "active" : "inactive"));
        return agent;
    }

    public record PayoutRequest(@NotNull @Min(1) BigDecimal amount) {
    }

    /**
     * Commission the operator moved themselves. Recorded through the payout
     * ledger rather than straight onto the agent, so their paid total has one
     * explanation whether the money went out by hand or on a schedule.
     */
    @PreAuthorize("hasAuthority('FINANCE')")
    @PostMapping("/agents/{id}/commission")
    public Agent payCommission(@PathVariable Long id, @Valid @RequestBody PayoutRequest request, Principal principal) {
        agentPayouts.recordManual(id, request.amount(), principal == null ? "office" : principal.getName());
        Agent agent = agentService.agentById(id);
        audit.record(principal, "agent.commission",
                "Recorded KES " + request.amount() + " commission paid by hand to " + agent.getFullName());
        return agent;
    }

    // --- Commission payouts ---

    /** What is owed, what has been paid, and how the schedule is set. */
    @PreAuthorize("hasAuthority('FINANCE')")
    @GetMapping("/agents/payouts")
    public Map<String, Object> payouts() {
        return Map.of(
                "settings", agentPayouts.settings(),
                "due", agentPayouts.due().stream().map(d -> Map.of(
                        "agentId", d.agent().getId(),
                        "agentName", d.agent().getFullName(),
                        "code", d.agent().getCode(),
                        "phoneNumber", d.agent().getPhoneNumber() == null ? "" : d.agent().getPhoneNumber(),
                        "owed", d.owed(),
                        "blockedBecause", d.blockedBecause() == null ? "" : d.blockedBecause())).toList(),
                "history", agentPayouts.history(),
                "canSendMoney", agentPayouts.canSendMoney());
    }

    @PreAuthorize("hasAuthority('SETTINGS')")
    @PutMapping("/agents/payouts/settings")
    public AgentPayoutSettings savePayoutSettings(@RequestBody AgentPayoutSettings body, Principal principal) {
        AgentPayoutSettings saved = agentPayouts.update(body);
        audit.record(principal, "agent.payout.settings",
                "Updated agent commission payouts (" + (saved.isEnabled() ? "on" : "off")
                        + (saved.isAutoSend() ? ", automatic" : ", needs release") + ")");
        return saved;
    }

    /** Works out this round of payouts now instead of waiting for the schedule. */
    @PreAuthorize("hasAuthority('FINANCE')")
    @PostMapping("/agents/payouts/run")
    public Map<String, Object> runPayouts(Principal principal) {
        Map<String, Object> result = agentPayouts.runNow(principal == null ? "office" : principal.getName());
        audit.record(principal, "agent.payout.run",
                "Prepared " + result.get("queued") + " commission payout(s) totalling KES " + result.get("total"));
        return result;
    }

    @PreAuthorize("hasAuthority('FINANCE')")
    @PostMapping("/agents/payouts/{id}/release")
    public CommissionPayout releasePayout(@PathVariable Long id, Principal principal) {
        CommissionPayout payout = agentPayouts.release(id);
        audit.record(principal, "agent.payout.release",
                "Released commission payout #" + id + " (KES " + payout.getAmount() + ")");
        return payout;
    }

    @PreAuthorize("hasAuthority('FINANCE')")
    @PostMapping("/agents/payouts/{id}/cancel")
    public CommissionPayout cancelPayout(@PathVariable Long id, Principal principal) {
        CommissionPayout payout = agentPayouts.cancel(id);
        audit.record(principal, "agent.payout.cancel", "Cancelled commission payout #" + id);
        return payout;
    }

    @PreAuthorize("hasAuthority('SELL')")
    @DeleteMapping("/agents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgent(@PathVariable Long id, Principal principal) {
        audit.record(principal, "agent.delete", "Removed agent " + id);
        agentService.deleteAgent(id);
    }

    // --- Batches ---

    @PreAuthorize("hasAuthority('SELL')")
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

    @PreAuthorize("hasAuthority('SELL')")
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

    @PreAuthorize("hasAuthority('SELL')")
    @PatchMapping("/batches/{id}/assign")
    public VoucherBatch assignBatch(@PathVariable Long id, @RequestBody AssignRequest request, Principal principal) {
        VoucherBatch batch = agentService.assignBatch(id, request.agentId());
        audit.record(principal, "batch.assign", batch.getReference()
                + (request.agentId() == null ? " returned to head office" : " assigned to agent " + request.agentId()));
        return batch;
    }

    @PreAuthorize("hasAuthority('SELL')")
    @GetMapping("/batches/{id}/vouchers")
    public List<Voucher> batchVouchers(@PathVariable Long id) {
        return agentService.vouchersInBatch(id);
    }
}
