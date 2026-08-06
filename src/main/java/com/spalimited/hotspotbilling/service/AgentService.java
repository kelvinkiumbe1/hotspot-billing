package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Voucher batches and the agents who resell them. Sales and commission are
 * derived from the vouchers in an agent's batches that customers have
 * actually used, so the numbers can never drift from reality.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final AgentRepository agents;
    private final VoucherBatchRepository batches;
    private final VoucherRepository vouchers;
    private final PlanRepository plans;
    private final VoucherService voucherService;
    private final CustomPlanService customPlanService;

    // --- Agents ---

    @Transactional
    public Agent createAgent(String fullName, String phoneNumber, String code,
                             int commissionPercent, String location) {
        String normalized = code.trim().toUpperCase();
        agents.findByCode(normalized).ifPresent(existing -> {
            throw new IllegalArgumentException("Agent code already taken: " + normalized);
        });
        if (commissionPercent < 0 || commissionPercent > 60) {
            throw new IllegalArgumentException("Commission must be between 0 and 60 percent");
        }
        return agents.save(Agent.builder()
                .fullName(fullName)
                .phoneNumber(phoneNumber)
                .code(normalized)
                .commissionPercent(commissionPercent)
                .location(location)
                .build());
    }

    @Transactional
    public Agent toggleAgent(Long id) {
        Agent agent = agent(id);
        agent.setActive(!agent.isActive());
        return agents.save(agent);
    }

    @Transactional
    public Agent recordCommissionPayout(Long id, BigDecimal amount) {
        Agent agent = agent(id);
        agent.setCommissionPaid(agent.getCommissionPaid().add(amount));
        return agents.save(agent);
    }

    @Transactional
    public void deleteAgent(Long id) {
        Agent agent = agent(id);
        batches.findByAgentId(id).forEach(b -> {
            b.setAgentId(null);
            batches.save(b);
        });
        agents.delete(agent);
    }

    /** Agents with their derived stock, sales and commission figures. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> agentScoreboard() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Agent agent : agents.findAllByOrderByFullNameAsc()) {
            Map<String, Object> row = new LinkedHashMap<>(salesFor(agent));
            row.put("id", agent.getId());
            row.put("fullName", agent.getFullName());
            row.put("phoneNumber", agent.getPhoneNumber());
            row.put("code", agent.getCode());
            row.put("commissionPercent", agent.getCommissionPercent());
            row.put("location", agent.getLocation());
            row.put("active", agent.isActive());
            row.put("commissionPaid", agent.getCommissionPaid());
            out.add(row);
        }
        return out;
    }

    /** Stock held, vouchers sold, face value and commission for one agent. */
    @Transactional(readOnly = true)
    public Map<String, Object> salesFor(Agent agent) {
        List<VoucherBatch> agentBatches = batches.findByAgentId(agent.getId());
        Set<Long> batchIds = new HashSet<>();
        agentBatches.forEach(b -> batchIds.add(b.getId()));

        int stock = 0;
        int sold = 0;
        BigDecimal faceValue = BigDecimal.ZERO;
        for (Voucher voucher : vouchers.findAll()) {
            if (voucher.getBatchId() == null || !batchIds.contains(voucher.getBatchId())) {
                continue;
            }
            if (voucher.getStatus() == Voucher.Status.UNUSED) {
                stock++;
            } else {
                sold++;
                faceValue = faceValue.add(voucher.getPlan().getPrice());
            }
        }
        BigDecimal commissionEarned = faceValue
                .multiply(BigDecimal.valueOf(agent.getCommissionPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batches", agentBatches.size());
        out.put("stock", stock);
        out.put("sold", sold);
        out.put("faceValue", faceValue);
        out.put("commissionEarned", commissionEarned);
        out.put("commissionOwed", commissionEarned.subtract(agent.getCommissionPaid()).max(BigDecimal.ZERO));
        return out;
    }

    // --- Batches ---

    private String nextReference() {
        long next = batches.countByReferenceStartingWith("BATCH-") + 1;
        return "BATCH-" + String.format("%06d", next);
    }

    /**
     * Generates a run of vouchers and records it as a batch, optionally
     * handing the stock to an agent.
     */
    @Transactional
    public VoucherBatch createBatch(Long planId, Integer customMinutes, int count, String prefix,
                                    Integer codeLength, Long agentId, String note, String createdBy) {
        if (agentId != null) {
            agent(agentId); // fail fast on an unknown agent
        }
        Plan plan = customMinutes != null
                ? customPlanService.systemPlan(customPlanService.settings())
                : plans.findById(Objects.requireNonNull(planId, "Choose a plan or a custom duration"))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + planId));

        VoucherBatch batch = batches.save(VoucherBatch.builder()
                .reference(nextReference())
                .plan(plan)
                .customMinutes(customMinutes)
                .count(count)
                .prefix(prefix)
                .codeLength(codeLength)
                .agentId(agentId)
                .note(note)
                .createdBy(createdBy)
                .build());

        List<Voucher> issued = IntStream.range(0, count)
                .mapToObj(i -> customMinutes != null
                        ? voucherService.issueCustom(plan, null, customMinutes, prefix, codeLength, createdBy)
                        : voucherService.issue(plan, null, prefix, codeLength, createdBy))
                .toList();
        issued.forEach(v -> {
            v.setBatchId(batch.getId());
            vouchers.save(v);
        });
        log.info("Created {} with {} voucher(s)", batch.getReference(), count);
        return batch;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> batchList() {
        Map<Long, String> agentNames = new HashMap<>();
        agents.findAll().forEach(a -> agentNames.put(a.getId(), a.getFullName() + " (" + a.getCode() + ")"));

        Map<Long, int[]> tallies = new HashMap<>(); // batchId -> [unused, used]
        for (Voucher voucher : vouchers.findAll()) {
            if (voucher.getBatchId() == null) {
                continue;
            }
            int[] tally = tallies.computeIfAbsent(voucher.getBatchId(), k -> new int[2]);
            if (voucher.getStatus() == Voucher.Status.UNUSED) {
                tally[0]++;
            } else {
                tally[1]++;
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (VoucherBatch batch : batches.findAllByOrderByCreatedAtDesc()) {
            int[] tally = tallies.getOrDefault(batch.getId(), new int[2]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.getId());
            row.put("reference", batch.getReference());
            row.put("planName", batch.getCustomMinutes() != null
                    ? "Custom · " + batch.getCustomMinutes() + " min"
                    : batch.getPlan().getName());
            row.put("count", batch.getCount());
            row.put("unused", tally[0]);
            row.put("used", tally[1]);
            row.put("prefix", batch.getPrefix());
            row.put("agentId", batch.getAgentId());
            row.put("agentName", batch.getAgentId() != null ? agentNames.get(batch.getAgentId()) : null);
            row.put("createdBy", batch.getCreatedBy());
            row.put("note", batch.getNote());
            row.put("createdAt", batch.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** Hands an existing batch to an agent, or takes it back to head office. */
    @Transactional
    public VoucherBatch assignBatch(Long batchId, Long agentId) {
        VoucherBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown batch: " + batchId));
        if (agentId != null) {
            agent(agentId);
        }
        batch.setAgentId(agentId);
        return batches.save(batch);
    }

    /** The voucher codes in a batch, for printing or handing over. */
    @Transactional(readOnly = true)
    public List<Voucher> vouchersInBatch(Long batchId) {
        return vouchers.findAll().stream()
                .filter(v -> batchId.equals(v.getBatchId()))
                .toList();
    }

    private Agent agent(Long id) {
        return agents.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + id));
    }
}
