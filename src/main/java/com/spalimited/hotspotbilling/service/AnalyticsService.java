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
    private final TrafficUsageRepository trafficUsage;
    private final RouterRepository routers;

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
        // Parallel to perDay: [0] settled transactions that day, [1] new
        // subscribers that day. Kept as counts rather than money so the
        // headline cards each chart a genuinely different daily series
        // instead of three views of the same revenue line.
        Map<LocalDate, int[]> perDayCounts = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            perDay.put(d, new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
            perDayCounts.put(d, new int[] { 0, 0 });
        }

        int[] byHour = new int[24];
        int[] byWeekday = new int[7];
        Map<String, BigDecimal> planRevenue = new HashMap<>();
        Map<String, Integer> planSales = new HashMap<>();

        // Highest-paying customers, keyed by phone number across both sides of
        // the business. spendByPhone holds the money; labelByPhone prefers a
        // known subscriber's name over the bare number when we have one.
        Map<String, BigDecimal> spendByPhone = new HashMap<>();
        Map<String, String> labelByPhone = new HashMap<>();

        for (Payment p : hotspotPaid) {
            LocalDate d = day(paidAt(p.getCompletedAt(), p.getCreatedAt()));
            BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
            if (!d.isBefore(from)) {
                hotspotNow = hotspotNow.add(amount);
                BigDecimal[] slot = perDay.get(d);
                if (slot != null) {
                    slot[0] = slot[0].add(amount);
                    perDayCounts.get(d)[0]++;
                }
                ZonedDateTime when = paidAt(p.getCompletedAt(), p.getCreatedAt()).atZone(ZONE);
                byHour[when.getHour()]++;
                byWeekday[when.getDayOfWeek().getValue() - 1]++;

                String plan = p.getCustomMinutes() != null
                        ? "Custom minutes"
                        : (p.getPlan() != null ? p.getPlan().getName() : "Unknown");
                planRevenue.merge(plan, amount, BigDecimal::add);
                planSales.merge(plan, 1, Integer::sum);

                if (p.getPhoneNumber() != null && !p.getPhoneNumber().isBlank()) {
                    spendByPhone.merge(p.getPhoneNumber(), amount, BigDecimal::add);
                }
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
                    perDayCounts.get(d)[0]++;
                }
                if (p.getMethod() == SubscriptionPayment.Method.CASH) {
                    pppoeCash = pppoeCash.add(amount);
                } else {
                    pppoeMpesa = pppoeMpesa.add(amount);
                }
                Subscriber sub = p.getSubscriber();
                if (sub != null && sub.getPhoneNumber() != null && !sub.getPhoneNumber().isBlank()) {
                    spendByPhone.merge(sub.getPhoneNumber(), amount, BigDecimal::add);
                    if (sub.getFullName() != null && !sub.getFullName().isBlank()) {
                        labelByPhone.put(sub.getPhoneNumber(), sub.getFullName());
                    }
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
        for (Subscriber s : allSubs) {
            if (s.getCreatedAt() != null) {
                int[] c = perDayCounts.get(day(s.getCreatedAt()));
                if (c != null) {
                    c[1]++;
                }
            }
        }
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
                        "total", e.getValue()[0].add(e.getValue()[1]),
                        "count", perDayCounts.get(e.getKey())[0],
                        "signups", perDayCounts.get(e.getKey())[1]))
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

        out.put("topCustomers", spendByPhone.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(8)
                .map(e -> Map.<String, Object>of(
                        "name", labelByPhone.getOrDefault(e.getKey(), maskPhone(e.getKey())),
                        "phone", maskPhone(e.getKey()),
                        "spent", e.getValue()))
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
     * Data-usage reports, aggregated from the traffic_usage rows the monitor
     * job captures from the routers. All of these were empty before capture
     * shipped, and only fill in as traffic accrues — there is no backfill.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> traffic(int days) {
        int window = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = today.minusDays(window - 1L);
        Instant fromInstant = from.atStartOfDay(ZONE).toInstant();
        Instant previousFromInstant = from.minusDays(window).atStartOfDay(ZONE).toInstant();

        // One read covers this window and the one before it (for current-vs-previous).
        List<TrafficUsage> withPrev = trafficUsage.findByBucketHourGreaterThanEqual(previousFromInstant);
        List<TrafficUsage> rows = new ArrayList<>();
        long prevTotal = 0;
        long nowTotal = 0;
        for (TrafficUsage r : withPrev) {
            long bytes = r.getBytesUp() + r.getBytesDown();
            if (!r.getBucketHour().isBefore(fromInstant)) {
                rows.add(r);
                nowTotal += bytes;
            } else {
                prevTotal += bytes;
            }
        }

        Map<Long, String> routerNames = new HashMap<>();
        routers.findAll().forEach(rt -> routerNames.put(rt.getId(), rt.getName()));

        Map<Long, long[]> perRouterBytes = new HashMap<>(); // [up, down]
        Map<Long, Set<String>> perRouterUsers = new HashMap<>();
        Map<Long, Long> planBytes = new HashMap<>();
        Map<String, Long> talkerBytes = new HashMap<>();
        Map<String, Set<String>> heatmapUsers = new HashMap<>(); // "weekday:hour" -> users
        long totalUp = 0;
        long totalDown = 0;

        for (TrafficUsage r : rows) {
            long up = r.getBytesUp();
            long down = r.getBytesDown();
            totalUp += up;
            totalDown += down;

            perRouterBytes.computeIfAbsent(r.getRouterId(), k -> new long[2]);
            perRouterBytes.get(r.getRouterId())[0] += up;
            perRouterBytes.get(r.getRouterId())[1] += down;
            perRouterUsers.computeIfAbsent(r.getRouterId(), k -> new HashSet<>()).add(r.getUserKey());

            if (r.getPlanId() != null) {
                planBytes.merge(r.getPlanId(), up + down, Long::sum);
            }
            talkerBytes.merge(r.getUserKey(), up + down, Long::sum);

            ZonedDateTime when = r.getBucketHour().atZone(ZONE);
            String cell = (when.getDayOfWeek().getValue() - 1) + ":" + when.getHour();
            heatmapUsers.computeIfAbsent(cell, k -> new HashSet<>()).add(r.getUserKey());
        }

        // Per-router revenue: attribute each settled hotspot payment to the
        // router its voucher was seen on (null until traffic captured it).
        Map<Long, BigDecimal> routerRevenue = new HashMap<>();
        for (Payment p : payments.findAll()) {
            if (!settled(p) || p.getVoucher() == null || p.getVoucher().getRouterId() == null) {
                continue;
            }
            if (day(paidAt(p.getCompletedAt(), p.getCreatedAt())).isBefore(from)) {
                continue;
            }
            BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
            routerRevenue.merge(p.getVoucher().getRouterId(), amount, BigDecimal::add);
        }

        List<Map<String, Object>> perRouter = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : perRouterBytes.entrySet()) {
            Long rid = e.getKey();
            perRouter.add(Map.of(
                    "router", routerNames.getOrDefault(rid, "Router #" + rid),
                    "bytesUp", e.getValue()[0],
                    "bytesDown", e.getValue()[1],
                    "bytes", e.getValue()[0] + e.getValue()[1],
                    "users", perRouterUsers.getOrDefault(rid, Set.of()).size(),
                    "revenue", routerRevenue.getOrDefault(rid, BigDecimal.ZERO)));
        }
        perRouter.sort((a, b) -> Long.compare((long) b.get("bytes"), (long) a.get("bytes")));

        Map<Long, String> planNames = new HashMap<>();
        vouchers.findAll().forEach(v -> {
            if (v.getPlan() != null) {
                planNames.putIfAbsent(v.getPlan().getId(), v.getPlan().getName());
            }
        });
        List<Map<String, Object>> usageByPlan = planBytes.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .map(e -> Map.<String, Object>of(
                        "plan", planNames.getOrDefault(e.getKey(), "Plan #" + e.getKey()),
                        "bytes", e.getValue()))
                .toList();

        List<Map<String, Object>> topTalkers = talkerBytes.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    String phone = vouchers.findByCode(e.getKey())
                            .map(Voucher::getPhoneNumber).orElse(null);
                    String label = phone != null && !phone.isBlank() ? maskPhone(phone) : e.getKey();
                    return Map.<String, Object>of("user", label, "bytes", e.getValue());
                })
                .toList();

        List<Map<String, Object>> heatmap = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : heatmapUsers.entrySet()) {
            String[] parts = e.getKey().split(":");
            heatmap.add(Map.of(
                    "weekday", Integer.parseInt(parts[0]),
                    "hour", Integer.parseInt(parts[1]),
                    "users", e.getValue().size()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", window);
        out.put("hasData", !rows.isEmpty());
        out.put("perRouter", perRouter);
        out.put("usageByPlan", usageByPlan);
        out.put("topTalkers", topTalkers);
        out.put("heatmap", heatmap);
        out.put("uploadDownload", Map.of("up", totalUp, "down", totalDown));

        Map<String, Object> dataUsage = new LinkedHashMap<>();
        dataUsage.put("current", nowTotal);
        dataUsage.put("previous", prevTotal);
        dataUsage.put("changePercent", percentChange(
                BigDecimal.valueOf(prevTotal), BigDecimal.valueOf(nowTotal)));
        out.put("dataUsage", dataUsage);
        return out;
    }

    /** Show enough of a number to recognise a customer without printing it in full. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return phone;
        }
        String last3 = phone.substring(phone.length() - 3);
        return "•••• " + last3;
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
