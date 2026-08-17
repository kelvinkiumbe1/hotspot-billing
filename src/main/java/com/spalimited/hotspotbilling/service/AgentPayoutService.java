package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Agent;
import com.spalimited.hotspotbilling.domain.AgentPayoutSettings;
import com.spalimited.hotspotbilling.domain.CommissionPayout;
import com.spalimited.hotspotbilling.repository.AgentPayoutSettingsRepository;
import com.spalimited.hotspotbilling.repository.AgentRepository;
import com.spalimited.hotspotbilling.repository.CommissionPayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Paying agents what they have earned, without anybody working it out by hand.
 *
 * <p>The commission figure was never the problem — it is derived from the
 * vouchers in an agent's batches that customers actually used, so it cannot
 * drift. The problem was the paying: work out the list, send each one M-Pesa,
 * then remember to come back and record it. The recording is the step that
 * gets skipped, and an agent who was paid but not recorded gets paid again.
 *
 * <p>Two rules hold this together. An agent's paid total moves only when
 * Safaricom confirms the money landed — a B2C request that Daraja merely
 * accepted has not paid anybody. And an agent with a payout in flight is
 * skipped entirely, because until it settles their balance still reads as
 * owed and a second run would pay it twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentPayoutService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** How long a B2C request may sit unanswered before the operator is told. */
    private static final Duration STALE_AFTER = Duration.ofHours(6);

    private final AgentPayoutSettingsRepository settingsRepo;
    private final CommissionPayoutRepository payouts;
    private final AgentRepository agents;
    private final AgentService agentService;
    private final MpesaService mpesa;
    private final SmsService smsService;
    private final MessagingSettingsService messagingSettings;
    private final PortalSettingsService portalSettings;

    // --- Settings ---

    @Transactional
    public AgentPayoutSettings settings() {
        return settingsRepo.findById(AgentPayoutSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(AgentPayoutSettings.builder()
                        .id(AgentPayoutSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public AgentPayoutSettings update(AgentPayoutSettings in) {
        AgentPayoutSettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setAutoSend(in.isAutoSend());
        s.setMinimumAmount(positive(in.getMinimumAmount(), new BigDecimal("500")));
        s.setMaxPerRun(positive(in.getMaxPerRun(), new BigDecimal("20000")));
        s.setFrequency(in.getFrequency() == null ? AgentPayoutSettings.Frequency.WEEKLY : in.getFrequency());
        s.setDayOfWeek(clamp(in.getDayOfWeek(), 1, 7));
        s.setDayOfMonth(clamp(in.getDayOfMonth(), 1, 31));
        s.setRunHour(clamp(in.getRunHour(), 0, 23));
        s.setB2cShortCode(in.getB2cShortCode() == null || in.getB2cShortCode().isBlank()
                ? null : in.getB2cShortCode().trim());
        return settingsRepo.save(s);
    }

    // --- What is owed ---

    /** An agent, what they are owed, and whether anything is stopping payment. */
    public record Due(Agent agent, BigDecimal owed, String blockedBecause) {
    }

    /**
     * Who would be paid on the next run. Agents that cannot be paid are still
     * listed, with the reason — an agent quietly missing from the run because
     * nobody typed their phone number is exactly the failure this replaces.
     */
    @Transactional(readOnly = true)
    public List<Due> due() {
        AgentPayoutSettings s = settings();
        Set<Long> inFlight = inFlightAgentIds();
        List<Due> out = new ArrayList<>();
        for (Agent agent : agents.findAllByOrderByFullNameAsc()) {
            if (!agent.isActive()) {
                continue;
            }
            BigDecimal owed = owed(agent);
            if (owed.compareTo(s.getMinimumAmount()) < 0) {
                continue;
            }
            String blocked = null;
            if (inFlight.contains(agent.getId())) {
                blocked = "a payout is already in flight";
            } else if (normalize(agent.getPhoneNumber()) == null) {
                blocked = "no usable M-Pesa number on their record";
            }
            out.add(new Due(agent, owed, blocked));
        }
        return out;
    }

    private BigDecimal owed(Agent agent) {
        Object value = agentService.salesFor(agent).get("commissionOwed");
        return value instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }

    private Set<Long> inFlightAgentIds() {
        return payouts.findByStatusInOrderByCreatedAtAsc(
                        List.of(CommissionPayout.Status.PENDING, CommissionPayout.Status.SENT))
                .stream()
                .map(CommissionPayout::getAgentId)
                .collect(java.util.stream.Collectors.toSet());
    }

    // --- The run ---

    /**
     * Works out the round of payouts and queues them, releasing each one
     * straight away when auto-send is on. Stops at the per-run ceiling rather
     * than paying part of an agent: half a commission is harder to explain
     * than one that waits for next week.
     */
    @Transactional
    public Map<String, Object> runNow(String createdBy) {
        AgentPayoutSettings s = settings();
        BigDecimal budget = s.getMaxPerRun();
        int queued = 0;
        int sent = 0;
        int skipped = 0;
        BigDecimal total = BigDecimal.ZERO;

        for (Due d : due()) {
            if (d.blockedBecause() != null) {
                skipped++;
                continue;
            }
            if (d.owed().compareTo(budget) > 0) {
                log.info("Agent {} is owed {} but only {} is left in this run — leaving it to roll over",
                        d.agent().getCode(), d.owed(), budget);
                skipped++;
                continue;
            }
            CommissionPayout payout = payouts.save(CommissionPayout.builder()
                    .agentId(d.agent().getId())
                    .amount(d.owed().setScale(2, RoundingMode.DOWN))
                    .status(CommissionPayout.Status.PENDING)
                    .createdBy(createdBy)
                    .build());
            queued++;
            budget = budget.subtract(d.owed());
            total = total.add(d.owed());
            if (s.isAutoSend() && release(payout.getId()).getStatus() == CommissionPayout.Status.SENT) {
                sent++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", queued);
        out.put("sent", sent);
        out.put("skipped", skipped);
        out.put("total", total);
        out.put("autoSend", s.isAutoSend());
        return out;
    }

    /**
     * Hands one queued payout to Daraja. Failure here is recorded against the
     * payout and returned, never thrown — one agent's bad phone number must
     * not abort a run halfway through paying everyone else.
     */
    @Transactional
    public CommissionPayout release(Long payoutId) {
        CommissionPayout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payout: " + payoutId));
        if (payout.getStatus() != CommissionPayout.Status.PENDING) {
            throw new IllegalStateException("That payout has already been " + payout.getStatus());
        }
        Agent agent = agents.findById(payout.getAgentId()).orElse(null);
        String phone = agent == null ? null : normalize(agent.getPhoneNumber());
        if (phone == null) {
            return fail(payout, "No usable M-Pesa number on the agent's record");
        }
        if (!mpesa.canSendMoney()) {
            return fail(payout, "M-Pesa payouts are not set up — add the initiator name and security "
                    + "credential under Settings → Payment gateways");
        }

        String conversationId = mpesa.b2cPayment(phone, payout.getAmount(),
                settings().getB2cShortCode(), "Commission " + agent.getCode());
        if (conversationId == null) {
            return fail(payout, "Safaricom did not accept the payment request");
        }
        payout.setStatus(CommissionPayout.Status.SENT);
        payout.setConversationId(conversationId);
        payout.setSentAt(Instant.now());
        return payouts.save(payout);
    }

    private CommissionPayout fail(CommissionPayout payout, String why) {
        payout.setStatus(CommissionPayout.Status.FAILED);
        payout.setError(why);
        payout.setCompletedAt(Instant.now());
        log.warn("Commission payout {} failed: {}", payout.getId(), why);
        return payouts.save(payout);
    }

    /**
     * The async B2C result. This is the only place an agent's paid total goes
     * up, because this is the only point at which the money has actually moved.
     */
    @Transactional
    public void handleB2cResult(JsonNode body) {
        JsonNode result = body.path("Result");
        String conversationId = result.path("ConversationID").asString("");
        if (conversationId.isBlank()) {
            log.warn("B2C result with no ConversationID: {}", body);
            return;
        }
        CommissionPayout payout = payouts.findByConversationId(conversationId).orElse(null);
        if (payout == null) {
            log.warn("B2C result for an unknown conversation {}", conversationId);
            return;
        }
        if (!payout.isInFlight()) {
            // Safaricom retries; settling twice would credit the agent twice.
            log.info("B2C result for payout {} arrived again — already {}", payout.getId(), payout.getStatus());
            return;
        }

        int code = result.path("ResultCode").asInt(-1);
        if (code != 0) {
            fail(payout, trim(result.path("ResultDesc").asString("Safaricom rejected the payment"), 500));
            alertOperator("⚠️ Commission payout of KES " + payout.getAmount().stripTrailingZeros().toPlainString()
                    + " failed: " + payout.getError());
            return;
        }

        payout.setStatus(CommissionPayout.Status.PAID);
        payout.setReceipt(receiptFrom(result));
        payout.setCompletedAt(Instant.now());
        payouts.save(payout);

        agents.findById(payout.getAgentId()).ifPresent(agent -> {
            agent.setCommissionPaid(agent.getCommissionPaid().add(payout.getAmount()));
            agents.save(agent);
            tellAgent(agent, payout);
        });
        log.info("Commission payout {} confirmed paid ({})", payout.getId(), payout.getReceipt());
    }

    /** Daraja returns the receipt inside a list of name/value pairs. */
    private static String receiptFrom(JsonNode result) {
        for (JsonNode item : result.path("ResultParameters").path("ResultParameter")) {
            if ("TransactionReceipt".equals(item.path("Key").asString(""))) {
                return item.path("Value").asString(null);
            }
        }
        return null;
    }

    private void tellAgent(Agent agent, CommissionPayout payout) {
        String phone = normalize(agent.getPhoneNumber());
        if (phone == null) {
            return;
        }
        String business = portalSettings.settings().getBusinessName();
        smsService.trySend(phone, "💰 " + (business == null || business.isBlank() ? "Your" : business + ":")
                + " commission of KES " + payout.getAmount().stripTrailingZeros().toPlainString()
                + " has been sent to this number"
                + (payout.getReceipt() == null ? "." : " (M-Pesa " + payout.getReceipt() + ").")
                + " Thank you for your work.");
    }

    /**
     * Money the operator moved themselves. Kept in the same ledger as the
     * automatic ones so the agent's paid total has exactly one explanation.
     */
    @Transactional
    public CommissionPayout recordManual(Long agentId, BigDecimal amount, String by) {
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentId));
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Enter an amount greater than zero");
        }
        agent.setCommissionPaid(agent.getCommissionPaid().add(amount));
        agents.save(agent);
        return payouts.save(CommissionPayout.builder()
                .agentId(agentId)
                .amount(amount)
                .status(CommissionPayout.Status.MANUAL)
                .createdBy(by)
                .completedAt(Instant.now())
                .build());
    }

    /** The recent payout ledger, with the agent's name resolved for display. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history() {
        Map<Long, Agent> byId = new LinkedHashMap<>();
        agents.findAll().forEach(a -> byId.put(a.getId(), a));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommissionPayout p : payouts.findTop200ByOrderByCreatedAtDesc()) {
            Agent agent = byId.get(p.getAgentId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("agentId", p.getAgentId());
            row.put("agentName", agent == null ? "#" + p.getAgentId() : agent.getFullName());
            row.put("code", agent == null ? "" : agent.getCode());
            row.put("amount", p.getAmount());
            row.put("status", p.getStatus().name());
            row.put("receipt", p.getReceipt());
            row.put("error", p.getError());
            row.put("createdBy", p.getCreatedBy());
            row.put("createdAt", p.getCreatedAt());
            row.put("completedAt", p.getCompletedAt());
            out.add(row);
        }
        return out;
    }

    /** Whether a release would actually be able to move money right now. */
    public boolean canSendMoney() {
        return mpesa.canSendMoney();
    }

    @Transactional
    public CommissionPayout cancel(Long payoutId) {
        CommissionPayout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown payout: " + payoutId));
        if (payout.getStatus() != CommissionPayout.Status.PENDING) {
            throw new IllegalStateException("Only a payout that hasn't been sent can be cancelled");
        }
        return fail(payout, "Cancelled before sending");
    }

    // --- Scheduling ---

    /**
     * Fires on the chosen day and hour, once. Everything about "is it due" is
     * decided here rather than in a cron expression, because the operator sets
     * the schedule from the admin and a cron string cannot be edited there.
     */
    @Transactional
    public void maybeRun() {
        AgentPayoutSettings s = settings();
        if (!s.isEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(ZONE);
        if (today.equals(s.getLastRunOn()) || LocalTime.now(ZONE).getHour() != s.getRunHour()) {
            return;
        }
        if (!isPayDay(s, today)) {
            return;
        }
        Map<String, Object> result = runNow("schedule");
        s.setLastRunOn(today);
        settingsRepo.save(s);
        log.info("Agent payout run: {}", result);

        if (!s.isAutoSend() && (int) result.get("queued") > 0) {
            alertOperator("💰 " + result.get("queued") + " agent commission payout(s) totalling KES "
                    + result.get("total") + " are ready to release under Agents → Payouts.");
        }
    }

    private static boolean isPayDay(AgentPayoutSettings s, LocalDate today) {
        if (s.getFrequency() == AgentPayoutSettings.Frequency.WEEKLY) {
            return today.getDayOfWeek().getValue() == s.getDayOfWeek();
        }
        // Clamped, so "the 31st" still pays on the last day of a short month.
        return today.getDayOfMonth() == Math.min(s.getDayOfMonth(), today.lengthOfMonth());
    }

    /**
     * A request Safaricom never answered. The status is deliberately left
     * alone: the money may well have moved, and marking it failed would invite
     * somebody to pay it a second time. It is a person's call, so a person is
     * told — once.
     */
    @Transactional
    public int flagStalePayouts() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        int flagged = 0;
        for (CommissionPayout p : payouts.findByStatusInOrderByCreatedAtAsc(
                List.of(CommissionPayout.Status.SENT))) {
            if (p.getError() != null || p.getSentAt() == null || p.getSentAt().isAfter(cutoff)) {
                continue;
            }
            p.setError("No result from Safaricom after " + STALE_AFTER.toHours()
                    + " hours — check the M-Pesa statement before paying this again");
            payouts.save(p);
            alertOperator("⚠️ Commission payout #" + p.getId() + " (KES "
                    + p.getAmount().stripTrailingZeros().toPlainString()
                    + ") has had no result from Safaricom in " + STALE_AFTER.toHours()
                    + " hours. Check your M-Pesa statement before sending it again.");
            flagged++;
        }
        return flagged;
    }

    // --- Plumbing ---

    private void alertOperator(String message) {
        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, message);
        }
    }

    private static BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 10 && d.startsWith("0")) {
            d = "254" + d.substring(1);
        }
        if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        return d.matches("254\\d{9}") ? d : null;
    }
}
