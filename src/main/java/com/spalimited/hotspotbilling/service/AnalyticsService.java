package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * The numbers an operator actually checks: what came in, from which side of
 * the business, which plans sell, whether vouchers are moving, and when
 * people buy. Everything is computed from the stored payments, vouchers and
 * subscribers on each request rather than kept in a rollup table, so a
 * refunded or corrected record shows up immediately.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final PaymentRepository payments;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final VoucherRepository vouchers;
    private final SubscriberRepository subscribers;
    private final ExpenseRepository expenses;
    private final LeadRepository leads;
    private final VoucherBatchRepository batches;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Successful payments only — pending and failed ones are not money. */
    private static boolean settled(Payment p) {
        return p.getStatus() == Payment.Status.SUCCESS;
    }

    private static boolean settled(SubscriptionPayment p) {
        return p.getStatus() == SubscriptionPayment.Status.SUCCESS;
    }

    /** When the money landed, falling back to when it was raised. */
    private static Instant paidAt(Instant completedAt, Instant createdAt) {
        return completedAt != null ? completedAt : createdAt;
    }

    private static LocalDate day(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(int days) {
        int window = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = today.minusDays(window - 1L);
        LocalDate previousFrom = from.minusDays(window);
        LocalDate previousTo = from.minusDays(1);

        List<Payment> hotspotPaid = payments.findAll().stream().filter(AnalyticsService::settled).toList();
        List<SubscriptionPayment> pppoePaid = subscriptionPayments.findAll().stream()
                .filter(AnalyticsService::settled).toList();

        // --- Revenue, this window against the one before it ---
        BigDecimal hotspotNow = BigDecimal.ZERO;
        BigDecimal pppoeNow = BigDecimal.ZERO;
        BigDecimal hotspotBefore = BigDecimal.ZERO;
        BigDecimal pppoeBefore = BigDecimal.ZERO;

        Map<LocalDate, BigDecimal[]> perDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            perDay.put(d, new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
        }

        int[] byHour = new int[24];
        int[] byWeekday = new int[7];
        Map<String, BigDecimal> planRevenue = new HashMap<>();
        Map<String, Integer> planSales = new HashMap<>();

        for (Payment p : hotspotPaid) {
            LocalDate d = day(paidAt(p.getCompletedAt(), p.getCreatedAt()));
            BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
            if (!d.isBefore(from)) {
                hotspotNow = hotspotNow.add(amount);
                BigDecimal[] slot = perDay.get(d);
                if (slot != null) {
                    slot[0] = slot[0].add(amount);
                }
                ZonedDateTime when = paidAt(p.getCompletedAt(), p.getCreatedAt()).atZone(ZONE);
                byHour[when.getHour()]++;
                byWeekday[when.getDayOfWeek().getValue() - 1]++;

                String plan = p.getCustomMinutes() != null
                        ? "Custom minutes"
                        : (p.getPlan() != null ? p.getPlan().getName() : "Unknown");
                planRevenue.merge(plan, amount, BigDecimal::add);
                planSales.merge(plan, 1, Integer::sum);
            } else if (!d.isBefore(previousFrom) && !d.isAfter(previousTo)) {
                hotspotBefore = hotspotBefore.add(amount);
            }
        }

        Map<String, BigDecimal> methodMix = new LinkedHashMap<>();
        methodMix.put("Hotspot M-Pesa", hotspotNow);
        BigDecimal pppoeMpesa = BigDecimal.ZERO;
        BigDecimal pppoeCash = BigDecimal.ZERO;

        for (SubscriptionPayment p : pppoePaid) {
            LocalDate d = day(paidAt(p.getCompletedAt(), p.getCreatedAt()));
            BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
            if (!d.isBefore(from)) {
                pppoeNow = pppoeNow.add(amount);
                BigDecimal[] slot = perDay.get(d);
                if (slot != null) {
                    slot[1] = slot[1].add(amount);
                }
                if (p.getMethod() == SubscriptionPayment.Method.CASH) {
                    pppoeCash = pppoeCash.add(amount);
                } else {
                    pppoeMpesa = pppoeMpesa.add(amount);
                }
            } else if (!d.isBefore(previousFrom) && !d.isAfter(previousTo)) {
                pppoeBefore = pppoeBefore.add(amount);
            }
        }
        methodMix.put("PPPoE M-Pesa", pppoeMpesa);
        methodMix.put("PPPoE cash", pppoeCash);

        BigDecimal totalNow = hotspotNow.add(pppoeNow);
        BigDecimal totalBefore = hotspotBefore.add(pppoeBefore);

        BigDecimal spend = expenses.findByIncurredOnBetweenOrderByIncurredOnDesc(from, today).stream()
                .map(Expense::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // --- Vouchers: are they moving, or piling up? ---
        int issuedInWindow = 0;
        int usedInWindow = 0;
        int stock = 0;
        // Both sides are the face value of vouchers redeemed in the window.
        // Comparing agent sales against settled M-Pesa payments would mix two
        // different bases — an agent's cash sale never passes through STK.
        BigDecimal agentValue = BigDecimal.ZERO;
        BigDecimal directValue = BigDecimal.ZERO;
        Set<Long> agentBatches = new HashSet<>();
        batches.findAll().forEach(b -> {
            if (b.getAgentId() != null) {
                agentBatches.add(b.getId());
            }
        });

        for (Voucher v : vouchers.findAll()) {
            if (v.getStatus() == Voucher.Status.UNUSED) {
                stock++;
            }
            if (v.getCreatedAt() != null && !day(v.getCreatedAt()).isBefore(from)) {
                issuedInWindow++;
            }
            if (v.getActivatedAt() != null && !day(v.getActivatedAt()).isBefore(from)) {
                usedInWindow++;
                BigDecimal face = v.getPlan() == null ? BigDecimal.ZERO : v.getPlan().getPrice();
                if (v.getBatchId() != null && agentBatches.contains(v.getBatchId())) {
                    agentValue = agentValue.add(face);
                } else {
                    directValue = directValue.add(face);
                }
            }
        }

        // --- Subscriber base ---
        List<Subscriber> allSubs = subscribers.findAll();
        long active = allSubs.stream().filter(s -> s.getStatus() == Subscriber.Status.ACTIVE).count();
        long suspended = allSubs.stream().filter(s -> s.getStatus() == Subscriber.Status.SUSPENDED).count();
        long newSubs = allSubs.stream()
                .filter(s -> s.getCreatedAt() != null && !day(s.getCreatedAt()).isBefore(from))
                .count();
        BigDecimal recurring = allSubs.stream()
                .filter(s -> s.getStatus() == Subscriber.Status.ACTIVE)
                .map(Subscriber::getMonthlyFee)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal arpu = active == 0 ? BigDecimal.ZERO
                : recurring.divide(BigDecimal.valueOf(active), 2, RoundingMode.HALF_UP);

        // --- Leads ---
        List<Lead> allLeads = leads.findAll();
        long leadsInWindow = allLeads.stream()
                .filter(l -> l.getCreatedAt() != null && !day(l.getCreatedAt()).isBefore(from))
                .count();
        long convertedInWindow = allLeads.stream()
                .filter(l -> l.getStatus() == Lead.Status.CONVERTED)
                .filter(l -> l.getCreatedAt() != null && !day(l.getCreatedAt()).isBefore(from))
                .count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", window);
        out.put("from", from.toString());
        out.put("to", today.toString());

        // A LinkedHashMap rather than Map.of: changePercent is null when there
        // is no earlier period to compare against, and Map.of rejects nulls.
        Map<String, Object> revenueOut = new LinkedHashMap<>();
        revenueOut.put("hotspot", hotspotNow);
        revenueOut.put("pppoe", pppoeNow);
        revenueOut.put("total", totalNow);
        revenueOut.put("previousTotal", totalBefore);
        revenueOut.put("changePercent", percentChange(totalBefore, totalNow));
        revenueOut.put("expenses", spend);
        revenueOut.put("net", totalNow.subtract(spend));
        out.put("revenue", revenueOut);

        out.put("perDay", perDay.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "date", e.getKey().toString(),
                        "hotspot", e.getValue()[0],
                        "pppoe", e.getValue()[1],
                        "total", e.getValue()[0].add(e.getValue()[1])))
                .toList());

        out.put("methodMix", methodMix.entrySet().stream()
                .filter(e -> e.getValue().signum() > 0)
                .map(e -> Map.<String, Object>of("label", e.getKey(), "amount", e.getValue()))
                .toList());

        out.put("topPlans", planRevenue.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(6)
                .map(e -> Map.<String, Object>of(
                        "plan", e.getKey(),
                        "revenue", e.getValue(),
                        "sales", planSales.getOrDefault(e.getKey(), 0)))
                .toList());

        List<Map<String, Object>> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hours.add(Map.of("hour", h, "count", byHour[h]));
        }
        out.put("byHour", hours);

        String[] weekdayNames = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        List<Map<String, Object>> weekdays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            weekdays.add(Map.of("day", weekdayNames[i], "count", byWeekday[i]));
        }
        out.put("byWeekday", weekdays);

        out.put("vouchers", Map.of(
                "issued", issuedInWindow,
                "used", usedInWindow,
                "stock", stock,
                "sellThroughPercent", issuedInWindow == 0 ? 0
                        : BigDecimal.valueOf(usedInWindow * 100.0 / issuedInWindow)
                                .setScale(1, RoundingMode.HALF_UP),
                "agentValue", agentValue,
                "directValue", directValue,
                "redeemedValue", agentValue.add(directValue)));

        out.put("subscribers", Map.of(
                "active", active,
                "suspended", suspended,
                "newInWindow", newSubs,
                "monthlyRecurring", recurring,
                "arpu", arpu));

        out.put("leads", Map.of(
                "created", leadsInWindow,
                "converted", convertedInWindow,
                "conversionPercent", leadsInWindow == 0 ? 0
                        : BigDecimal.valueOf(convertedInWindow * 100.0 / leadsInWindow)
                                .setScale(1, RoundingMode.HALF_UP)));
        return out;
    }

    /**
     * Growth against the previous window. Null when there is no baseline —
     * showing "+100%" against zero would overstate a first sale.
     */
    private static BigDecimal percentChange(BigDecimal before, BigDecimal now) {
        if (before == null || before.signum() == 0) {
            return null;
        }
        return now.subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before, 1, RoundingMode.HALF_UP);
    }
}
