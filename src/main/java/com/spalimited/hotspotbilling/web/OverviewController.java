package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import com.spalimited.hotspotbilling.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Everything the dashboard shows, in one call.
 *
 * <p>The Overview is the landing page for every role, so it cannot simply
 * call the finance and network endpoints — a support agent would collect a
 * screenful of 403s. Instead each section is assembled only if the caller
 * holds the permission that section's own endpoint would demand, and
 * omitted entirely otherwise. The page then renders what it was given
 * rather than guessing what it is allowed to ask for.
 */
@RestController
@RequestMapping("/api/admin/overview")
@RequiredArgsConstructor
public class OverviewController {

    private final PaymentRepository payments;
    private final com.spalimited.hotspotbilling.service.MoneyService moneyService;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final VoucherRepository vouchers;
    private final SubscriberRepository subscribers;
    private final RouterRepository routers;
    private final SupportTicketRepository tickets;
    private final FiberNodeRepository fiberNodes;
    private final LedgerService ledgerService;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static boolean can(Authentication auth, String permission) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(permission));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> overview(Authentication auth) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", LocalDate.now(ZONE).toString());

        if (can(auth, "FINANCE")) {
            out.put("money", money());
        }
        if (can(auth, "NETWORK")) {
            out.put("sessions", liveSessions());
        }
        out.put("attention", attention(auth));
        if (can(auth, "SELL")) {
            out.put("stock", voucherStock());
        }
        return out;
    }

    /** Today against yesterday, plus a fortnight of daily totals for a sparkline. */
    private Map<String, Object> money() {
        LocalDate today = LocalDate.now(ZONE);
        Map<LocalDate, BigDecimal> daily = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) {
            daily.put(today.minusDays(i), BigDecimal.ZERO);
        }

        int soldToday = 0;
        for (Payment p : payments.findAll()) {
            if (p.getStatus() != Payment.Status.SUCCESS) {
                continue;
            }
            LocalDate day = day(p.getCompletedAt() != null ? p.getCompletedAt() : p.getCreatedAt());
            daily.computeIfPresent(day, (k, v) -> v.add(nz(p.getAmount())));
            if (day.equals(today)) {
                soldToday++;
            }
        }
        for (SubscriptionPayment p : subscriptionPayments.findAll()) {
            if (p.getStatus() != SubscriptionPayment.Status.SUCCESS) {
                continue;
            }
            LocalDate day = day(p.getCompletedAt() != null ? p.getCompletedAt() : p.getCreatedAt());
            daily.computeIfPresent(day, (k, v) -> v.add(nz(p.getAmount())));
        }

        BigDecimal todayTotal = daily.getOrDefault(today, BigDecimal.ZERO);
        BigDecimal yesterday = daily.getOrDefault(today.minusDays(1), BigDecimal.ZERO);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", todayTotal);
        out.put("yesterday", yesterday);
        // Null rather than a made-up percentage when yesterday was zero —
        // "+100%" against nothing is not information.
        out.put("changePercent", yesterday.signum() == 0 ? null
                : todayTotal.subtract(yesterday)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(yesterday, 0, java.math.RoundingMode.HALF_UP));
        out.put("sold", soldToday);
        out.put("series", daily.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey().toString(), "amount", e.getValue()))
                .toList());
        return out;
    }

    /** Who is on the network now, from the last-seen stamp the router poll writes. */
    private Map<String, Object> liveSessions() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Subscriber s : subscribers.findAll()) {
            if (s.getLastSeenOnlineAt() == null || s.getLastSeenOnlineAt().isBefore(cutoff)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user", s.getPppoeUsername());
            row.put("name", s.getFullName());
            row.put("plan", s.getBandwidth());
            row.put("dataMb", s.getDataUsedMbOrZero());
            row.put("since", s.getLastSeenOnlineAt());
            rows.add(row);
        }
        long activeVouchers = vouchers.countByStatus(Voucher.Status.ACTIVE);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscribers", rows);
        out.put("hotspotActive", activeVouchers);
        out.put("total", rows.size() + activeVouchers);
        return out;
    }

    /**
     * What is wrong right now, worst first. Each entry names the thing and
     * where to go, so the panel is a to-do list rather than a scoreboard.
     */
    private List<Map<String, Object>> attention(Authentication auth) {
        List<Map<String, Object>> items = new ArrayList<>();

        if (can(auth, "NETWORK")) {
            List<String> offline = routers.findAll().stream()
                    .filter(r -> !r.isOnline())
                    .map(Router::getName)
                    .toList();
            if (!offline.isEmpty()) {
                items.add(item("critical", offline.size() + " router" + plural(offline.size()) + " offline",
                        String.join(", ", offline), "routers"));
            }
            long faults = fiberNodes.findByStatus(FiberNode.Status.FAULT).size();
            if (faults > 0) {
                items.add(item("critical", faults + " fibre fault" + plural(faults),
                        "on the plant map", "fiber"));
            }
        }

        if (can(auth, "FINANCE")) {
            List<Map<String, Object>> owing = ledgerService.outstanding().stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("owes")))
                    .toList();
            if (!owing.isEmpty()) {
                BigDecimal total = owing.stream()
                        .map(r -> (BigDecimal) r.get("balance"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                items.add(item("warning", owing.size() + " subscriber" + plural(owing.size()) + " unpaid",
                        moneyService.format(total.setScale(0, java.math.RoundingMode.HALF_UP)) + " overdue", "ledger"));
            }
        }

        if (can(auth, "CUSTOMERS")) {
            List<SupportTicket> open = tickets.findAll().stream()
                    .filter(t -> t.getStatus() != SupportTicket.Status.RESOLVED)
                    .filter(t -> t.getMessages().stream().noneMatch(TicketMessage::isFromAdmin))
                    .toList();
            if (!open.isEmpty()) {
                Instant oldest = open.stream()
                        .map(SupportTicket::getCreatedAt)
                        .min(Instant::compareTo)
                        .orElse(Instant.now());
                long days = Duration.between(oldest, Instant.now()).toDays();
                items.add(item("warning", open.size() + " ticket" + plural(open.size()) + " awaiting a reply",
                        days > 0 ? "oldest " + days + " day" + plural(days) : "all opened today", "support"));
            }
        }

        if (can(auth, "SELL")) {
            long unused = vouchers.countByStatus(Voucher.Status.UNUSED);
            if (unused == 0) {
                items.add(item("warning", "No vouchers left to sell",
                        "generate a batch before anyone asks", "vouchers"));
            } else if (unused < 10) {
                items.add(item("info", "Only " + unused + " voucher" + plural(unused) + " left",
                        "worth printing more", "vouchers"));
            }
        }
        return items;
    }

    private Map<String, Object> voucherStock() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unused", vouchers.countByStatus(Voucher.Status.UNUSED));
        out.put("active", vouchers.countByStatus(Voucher.Status.ACTIVE));
        return out;
    }

    private static Map<String, Object> item(String severity, String title, String detail, String tab) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", severity);
        out.put("title", title);
        out.put("detail", detail);
        out.put("tab", tab);
        return out;
    }

    private static String plural(long n) {
        return n == 1 ? "" : "s";
    }

    private static LocalDate day(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
