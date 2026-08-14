package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Revenue assurance: cross-checks the four records that should always agree —
 * money received, service issued, what the router is actually letting online,
 * and who is still connected — and reports everything that doesn't.
 *
 * <p>The checks fall into three families. <em>Money with no service</em>: a
 * payment that never produced a voucher, a PayBill payment left unmatched, one
 * receipt behind two sales. <em>Service with no money</em>: a voucher nobody
 * paid for and no member of staff issued, a sale settled below its price.
 * <em>Service the system never authorised</em>: a hotspot user or PPPoE secret
 * created straight on the router, a session still running on a spent pass, a
 * lapsed subscriber who was never suspended. That last family is the one that
 * matters most — access handed out on the device, outside billing, is invisible
 * to every report in the product until something like this looks for it.
 *
 * <p>Findings are keyed by fingerprint, so a problem seen on successive nights
 * ages in place instead of re-alerting, and one that stops being detected closes
 * itself. Checks that couldn't run (an unreachable router) close nothing —
 * silence from a check is not evidence that its findings went away.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RevenueAuditService {

    private static final Map<RevenueFinding.Kind, RevenueFinding.Severity> SEVERITY = Map.of(
            RevenueFinding.Kind.PAID_NO_SERVICE, RevenueFinding.Severity.HIGH,
            RevenueFinding.Kind.DUPLICATE_RECEIPT, RevenueFinding.Severity.HIGH,
            RevenueFinding.Kind.SERVICE_NO_PAYMENT, RevenueFinding.Severity.HIGH,
            RevenueFinding.Kind.GHOST_HOTSPOT_USER, RevenueFinding.Severity.HIGH,
            RevenueFinding.Kind.GHOST_PPPOE_SECRET, RevenueFinding.Severity.HIGH,
            RevenueFinding.Kind.UNAPPLIED_PAYMENT, RevenueFinding.Severity.MEDIUM,
            RevenueFinding.Kind.EXPIRED_STILL_ONLINE, RevenueFinding.Severity.MEDIUM,
            RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, RevenueFinding.Severity.MEDIUM,
            RevenueFinding.Kind.UNDERPAID, RevenueFinding.Severity.LOW);

    /** Checks that don't touch a router, so they always run and can always close. */
    private static final Set<RevenueFinding.Kind> OFFLINE_CHECKS = EnumSet.of(
            RevenueFinding.Kind.PAID_NO_SERVICE,
            RevenueFinding.Kind.DUPLICATE_RECEIPT,
            RevenueFinding.Kind.SERVICE_NO_PAYMENT,
            RevenueFinding.Kind.UNAPPLIED_PAYMENT,
            RevenueFinding.Kind.LAPSED_NOT_SUSPENDED,
            RevenueFinding.Kind.UNDERPAID);

    private static final Set<RevenueFinding.Kind> ROUTER_CHECKS = EnumSet.of(
            RevenueFinding.Kind.GHOST_HOTSPOT_USER,
            RevenueFinding.Kind.GHOST_PPPOE_SECRET,
            RevenueFinding.Kind.EXPIRED_STILL_ONLINE);

    /** A payment that has just landed hasn't had time to issue yet. */
    private static final Duration ISSUE_GRACE = Duration.ofMinutes(15);

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());

    private final RevenueFindingRepository findings;
    private final RevenueAuditSettingsRepository settingsRepo;
    private final PaymentRepository payments;
    private final VoucherRepository vouchers;
    private final C2bPaymentRepository c2bPayments;
    private final SubscriberRepository subscribers;
    private final PayCodeRepository payCodes;
    private final PromotionRepository promotions;
    private final RouterRepository routers;
    private final MikrotikService mikrotik;
    private final MessagingSettingsService messagingSettings;
    private final SmsService smsService;
    private final AuditService audit;

    /** One observation from a check, before it's merged with what's stored. */
    private record Draft(RevenueFinding.Kind kind, String subject, String detail, BigDecimal amount) {
    }

    // --- Settings ---

    @Transactional
    public RevenueAuditSettings settings() {
        return settingsRepo.findById(RevenueAuditSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(RevenueAuditSettings.builder()
                        .id(RevenueAuditSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public RevenueAuditSettings saveSettings(RevenueAuditSettings in) {
        RevenueAuditSettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setAlertOperator(in.isAlertOperator());
        s.setUnmatchedHours(clamp(in.getUnmatchedHours(), 1, 720));
        s.setLapsedGraceDays(clamp(in.getLapsedGraceDays(), 0, 90));
        s.setLookbackDays(clamp(in.getLookbackDays(), 1, 365));
        s.setIgnoredAccounts(in.getIgnoredAccounts());
        return settingsRepo.save(s);
    }

    // --- The sweep ---

    /**
     * Runs every check, merges what it found with the open findings, and closes
     * the ones that have cleared. Returns a summary for the caller to display.
     */
    @Transactional
    public Map<String, Object> sweep(String actor) {
        RevenueAuditSettings s = settings();
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofDays(s.getLookbackDays()));

        List<Draft> drafts = new ArrayList<>();
        drafts.addAll(paidButNothingIssued(since, now));
        drafts.addAll(duplicateReceipts(since));
        drafts.addAll(unappliedPaybill(s, now));
        drafts.addAll(serviceWithNoPayment(since));
        drafts.addAll(lapsedButNotSuspended(s, now));
        drafts.addAll(underpaidSales(since));

        // Router checks only mean anything when we could actually read the
        // device. A router we couldn't reach leaves its findings untouched.
        boolean routersChecked = false;
        List<String> unreachable = new ArrayList<>();
        if (mikrotik.settings().isEnabled()) {
            Set<String> ignored = ignoredAccounts(s);
            // Zero-touch activation names a hotspot user after the paying
            // device's MAC so the router logs it in by itself. Those are ours,
            // even though the name is not a voucher code.
            Set<String> ourMacs = new HashSet<>();
            vouchers.findAllBoundMacs().forEach(m -> ourMacs.add(m.toLowerCase()));
            payCodes.findAllMacAddresses().forEach(m -> ourMacs.add(m.toLowerCase()));
            for (Router router : routers.findByEnabledTrue()) {
                try {
                    drafts.addAll(ghostAccounts(router, ignored, ourMacs));
                    drafts.addAll(stillOnlineWithoutEntitlement(router));
                    routersChecked = true;
                } catch (Exception e) {
                    unreachable.add(router.getName());
                    log.debug("Revenue audit skipped router {}: {}", router.getName(), e.getMessage());
                }
            }
        }

        Set<RevenueFinding.Kind> ran = EnumSet.copyOf(OFFLINE_CHECKS);
        if (routersChecked && unreachable.isEmpty()) {
            ran.addAll(ROUTER_CHECKS);
        }

        int fresh = 0;
        int freshHigh = 0;
        int stillOpen = 0;
        Set<String> seen = new HashSet<>();
        for (Draft d : drafts) {
            String fingerprint = d.kind() + ":" + d.subject();
            if (!seen.add(fingerprint)) {
                continue; // the same thing spotted by two routers
            }
            RevenueFinding f = findings.findByFingerprint(fingerprint).orElse(null);
            boolean isNew = false;
            if (f == null) {
                f = RevenueFinding.builder()
                        .fingerprint(fingerprint)
                        .kind(d.kind())
                        .severity(SEVERITY.getOrDefault(d.kind(), RevenueFinding.Severity.MEDIUM))
                        .subject(d.subject())
                        .firstSeenAt(now)
                        .status(RevenueFinding.Status.OPEN)
                        .build();
                isNew = true;
            } else if (f.getStatus() == RevenueFinding.Status.RESOLVED) {
                // It came back. Re-open rather than leaving a closed row behind.
                f.setStatus(RevenueFinding.Status.OPEN);
                f.setResolvedAt(null);
                f.setResolvedBy(null);
                f.setNote(null);
                isNew = true;
            }
            f.setDetail(trim(d.detail(), 500));
            f.setAmount(d.amount());
            f.setLastSeenAt(now);
            findings.save(f);
            // Something the operator has already called expected stays closed,
            // however many nights it keeps turning up.
            if (f.getStatus() != RevenueFinding.Status.OPEN) {
                continue;
            }
            stillOpen++;
            if (isNew) {
                fresh++;
                if (f.getSeverity() == RevenueFinding.Severity.HIGH) {
                    freshHigh++;
                }
            }
        }

        int closed = 0;
        for (RevenueFinding f : findings.findByStatus(RevenueFinding.Status.OPEN)) {
            if (!ran.contains(f.getKind()) || seen.contains(f.getFingerprint())) {
                continue;
            }
            f.setStatus(RevenueFinding.Status.RESOLVED);
            f.setResolvedAt(now);
            f.setResolvedBy("system");
            f.setNote("Cleared on its own — no longer detected.");
            findings.save(f);
            closed++;
        }

        s.setLastRunAt(now);
        settingsRepo.save(s);

        if (freshHigh > 0 && s.isAlertOperator()) {
            String phone = messagingSettings.alertPhone();
            if (phone != null && !phone.isBlank()) {
                smsService.trySend(phone, "ALERT: the revenue audit found " + freshHigh
                        + " new serious issue(s). Open Revenue Guard in the admin console to review.");
            }
        }

        audit.record(actor, "revenue.audit", "Revenue audit: " + stillOpen + " open issue(s), "
                + fresh + " new, " + closed + " cleared"
                + (unreachable.isEmpty() ? "" : "; skipped unreachable router(s): " + String.join(", ", unreachable)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranAt", now);
        out.put("found", stillOpen);
        out.put("newFindings", fresh);
        out.put("closed", closed);
        out.put("routersChecked", routersChecked);
        out.put("skippedRouters", unreachable);
        return out;
    }

    // --- Money in, no service out ---

    /**
     * A payment Safaricom confirmed that never produced a voucher. The customer
     * has been charged and has nothing to show for it — the single worst thing
     * that can happen in this business, and today it is only ever noticed when
     * they complain.
     */
    private List<Draft> paidButNothingIssued(Instant since, Instant now) {
        List<Draft> out = new ArrayList<>();
        for (Payment p : payments.findByStatusAndCreatedAtAfter(Payment.Status.SUCCESS, since)) {
            if (p.getVoucher() != null) {
                continue;
            }
            Instant settled = p.getCompletedAt() != null ? p.getCompletedAt() : p.getCreatedAt();
            if (settled.isAfter(now.minus(ISSUE_GRACE))) {
                continue; // still in flight
            }
            out.add(new Draft(RevenueFinding.Kind.PAID_NO_SERVICE, "payment:" + p.getId(),
                    money(p.getAmount()) + " from " + p.getPhoneNumber() + " on " + DAY.format(settled)
                            + " was confirmed but no voucher was ever issued"
                            + (p.getMpesaReceiptNumber() != null ? " (receipt " + p.getMpesaReceiptNumber() + ")" : "")
                            + ".",
                    p.getAmount()));
        }
        return out;
    }

    /**
     * One M-Pesa receipt behind two or more successful payments. Safaricom
     * never reuses a receipt, so this is a replayed callback — the customer
     * paid once and got service twice.
     */
    private List<Draft> duplicateReceipts(Instant since) {
        Map<String, List<Payment>> byReceipt = new LinkedHashMap<>();
        for (Payment p : payments.findByStatusAndCreatedAtAfter(Payment.Status.SUCCESS, since)) {
            String receipt = p.getMpesaReceiptNumber();
            if (receipt == null || receipt.isBlank()) {
                continue;
            }
            byReceipt.computeIfAbsent(receipt.trim().toUpperCase(), k -> new ArrayList<>()).add(p);
        }
        List<Draft> out = new ArrayList<>();
        byReceipt.forEach((receipt, group) -> {
            if (group.size() < 2) {
                return;
            }
            BigDecimal leaked = group.get(0).getAmount().multiply(BigDecimal.valueOf(group.size() - 1L));
            out.add(new Draft(RevenueFinding.Kind.DUPLICATE_RECEIPT, "receipt:" + receipt,
                    "M-Pesa receipt " + receipt + " is behind " + group.size() + " separate payments — "
                            + "paid for once, service given " + group.size() + " times.",
                    leaked));
        });
        return out;
    }

    /** PayBill money that landed but was never applied to anybody's account. */
    private List<Draft> unappliedPaybill(RevenueAuditSettings s, Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(s.getUnmatchedHours()));
        List<Draft> out = new ArrayList<>();
        for (C2bPayment p : c2bPayments.findByStatusOrderByCreatedAtDesc(C2bPayment.Status.UNMATCHED)) {
            if (p.getCreatedAt().isAfter(cutoff)) {
                continue;
            }
            long hours = Duration.between(p.getCreatedAt(), now).toHours();
            out.add(new Draft(RevenueFinding.Kind.UNAPPLIED_PAYMENT, "c2b:" + p.getTransactionId(),
                    money(p.getAmount()) + " from " + (p.getPayerName() != null ? p.getPayerName() : p.getPhoneNumber())
                            + " (account \"" + (p.getBillRefNumber() == null ? "" : p.getBillRefNumber()) + "\")"
                            + " has sat unmatched for " + hours + " hours. Nobody has been credited for it.",
                    p.getAmount()));
        }
        return out;
    }

    // --- Service out, no money in ---

    /**
     * A voucher that nobody paid for, no batch produced, and no member of staff
     * put their name to. Every legitimate free issue — a trial, a loyalty
     * reward, a referral bonus, an agent sale — stamps its origin in
     * {@code createdBy}, so what's left is service that appeared from nowhere.
     */
    private List<Draft> serviceWithNoPayment(Instant since) {
        Set<Long> paidFor = new HashSet<>(payments.findAllVoucherIds());
        List<Draft> out = new ArrayList<>();
        for (Voucher v : vouchers.findByCreatedAtAfter(since)) {
            boolean explained = paidFor.contains(v.getId())
                    || (v.getCreatedBy() != null && !v.getCreatedBy().isBlank())
                    || v.getBatchId() != null;
            if (explained) {
                continue;
            }
            out.add(new Draft(RevenueFinding.Kind.SERVICE_NO_PAYMENT, "voucher:" + v.getCode(),
                    "Voucher " + v.getCode() + " (" + v.getPlan().getName() + ", worth "
                            + money(v.getPlan().getPrice()) + ") was issued on " + DAY.format(v.getCreatedAt())
                            + " with no payment, no batch and no staff member behind it.",
                    v.getPlan().getPrice()));
        }
        return out;
    }

    /**
     * A sale settled for less than it should have been. Any promotion running
     * at the time is applied first, so a genuine discount isn't mistaken for a
     * shortfall; pay-per-minute passes are skipped because their price comes
     * from the minutes bought, not the plan.
     */
    private List<Draft> underpaidSales(Instant since) {
        List<Promotion> allPromos = promotions.findAll();
        List<Draft> out = new ArrayList<>();
        for (Payment p : payments.findByStatusAndCreatedAtAfter(Payment.Status.SUCCESS, since)) {
            if (p.getCustomMinutes() != null || p.getPlan() == null) {
                continue;
            }
            Instant when = p.getCompletedAt() != null ? p.getCompletedAt() : p.getCreatedAt();
            BigDecimal expected = expectedPrice(p.getPlan().getPrice(), allPromos, when);
            if (p.getAmount().compareTo(expected) >= 0) {
                continue;
            }
            BigDecimal shortfall = expected.subtract(p.getAmount());
            out.add(new Draft(RevenueFinding.Kind.UNDERPAID, "payment:" + p.getId(),
                    "\"" + p.getPlan().getName() + "\" sold for " + money(p.getAmount()) + " on "
                            + DAY.format(when) + " — " + money(shortfall) + " below the "
                            + money(expected) + " it should have cost.",
                    shortfall));
        }
        return out;
    }

    /** The plan's price with whatever promotion was running at that moment. */
    private BigDecimal expectedPrice(BigDecimal listPrice, List<Promotion> allPromos, Instant when) {
        int discount = 0;
        for (Promotion promo : allPromos) {
            boolean covers = promo.getStartsAt().isBefore(when) && promo.getEndsAt().isAfter(when);
            if (covers) {
                discount = Math.max(discount, promo.getDiscountPercent());
            }
        }
        if (discount <= 0) {
            return listPrice;
        }
        return listPrice.multiply(BigDecimal.valueOf(100 - discount))
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
                .max(BigDecimal.ONE);
    }

    // --- Service the system never authorised ---

    /**
     * Accounts configured on the router that the billing system never sold.
     * Free internet handed out at the device — by a technician, a former
     * employee who still has the password, or whoever else can log in — is
     * invisible to every report in the product, because as far as billing is
     * concerned the customer doesn't exist. This is the check that finds it.
     */
    private List<Draft> ghostAccounts(Router router, Set<String> ignored, Set<String> ourMacs) {
        Map<String, List<String>> configured = mikrotik.configuredAccounts(router);
        List<Draft> out = new ArrayList<>();

        for (String name : configured.getOrDefault("hotspot", List.of())) {
            String lower = name.toLowerCase();
            if (ignored.contains(lower) || ourMacs.contains(lower) || vouchers.existsByCode(name)) {
                continue;
            }
            out.add(new Draft(RevenueFinding.Kind.GHOST_HOTSPOT_USER, "hotspot:" + router.getId() + ":" + name,
                    "Hotspot login \"" + name + "\" exists on " + router.getName()
                            + " but was never issued by the system — somebody created it on the router itself.",
                    null));
        }

        for (String name : configured.getOrDefault("pppoe", List.of())) {
            if (ignored.contains(name.toLowerCase()) || subscribers.findByPppoeUsername(name).isPresent()) {
                continue;
            }
            out.add(new Draft(RevenueFinding.Kind.GHOST_PPPOE_SECRET, "pppoe:" + router.getId() + ":" + name,
                    "PPPoE account \"" + name + "\" exists on " + router.getName()
                            + " but belongs to no customer — an unbilled connection.",
                    null));
        }
        return out;
    }

    /**
     * People still online who shouldn't be: a hotspot session on a pass that is
     * spent or expired, or a PPPoE session belonging to a suspended subscriber.
     * Either means an enforcement step didn't take on the router.
     */
    private List<Draft> stillOnlineWithoutEntitlement(Router router) {
        List<Draft> out = new ArrayList<>();
        for (Map<String, String> session : mikrotik.activeSessions(router)) {
            String user = session.get("user");
            if (user == null || user.isBlank()) {
                continue;
            }
            if ("hotspot".equals(session.get("kind"))) {
                Voucher v = vouchers.findByCode(user).orElse(null);
                if (v == null || (v.getStatus() != Voucher.Status.EXPIRED && !v.isExhausted())) {
                    continue; // unknown codes are the ghost check's business
                }
                out.add(new Draft(RevenueFinding.Kind.EXPIRED_STILL_ONLINE, "session:hotspot:" + user,
                        "Pass " + user + " is spent but is still connected on " + router.getName()
                                + " — the router never cut it off.",
                        v.getPlan() != null ? v.getPlan().getPrice() : null));
            } else if ("pppoe".equals(session.get("kind"))) {
                Subscriber sub = subscribers.findByPppoeUsername(user).orElse(null);
                if (sub == null || sub.getStatus() != Subscriber.Status.SUSPENDED) {
                    continue;
                }
                out.add(new Draft(RevenueFinding.Kind.EXPIRED_STILL_ONLINE, "session:pppoe:" + user,
                        sub.getFullName() + " (" + user + ") is suspended but is online on "
                                + router.getName() + " — the suspension didn't take.",
                        sub.getMonthlyFee()));
            }
        }
        return out;
    }

    /**
     * Subscribers past their paid-until date whom the suspension job never
     * switched off — free service running on, month after month.
     */
    private List<Draft> lapsedButNotSuspended(RevenueAuditSettings s, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(s.getLapsedGraceDays()));
        List<Draft> out = new ArrayList<>();
        for (Subscriber sub : subscribers.findByStatus(Subscriber.Status.ACTIVE)) {
            if (sub.getPaidUntil() == null || sub.getPaidUntil().isAfter(cutoff)) {
                continue;
            }
            long days = Duration.between(sub.getPaidUntil(), now).toDays();
            out.add(new Draft(RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, "subscriber:" + sub.getId(),
                    sub.getFullName() + " (" + sub.getPppoeUsername() + ") stopped paying " + days
                            + " day(s) ago on " + DAY.format(sub.getPaidUntil())
                            + " but is still marked active at " + money(sub.getMonthlyFee()) + "/month.",
                    sub.getMonthlyFee()));
        }
        return out;
    }

    // --- Reading and working the findings ---

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        // Worst first. Sorted here rather than in the query because the column
        // holds the enum's name, and alphabetically LOW would outrank MEDIUM.
        List<RevenueFinding> open = findings.findByStatus(RevenueFinding.Status.OPEN).stream()
                .sorted(Comparator.comparing(RevenueFinding::getSeverity)
                        .thenComparing(RevenueFinding::getLastSeenAt, Comparator.reverseOrder()))
                .toList();
        BigDecimal atRisk = open.stream()
                .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Long> byKind = new LinkedHashMap<>();
        for (RevenueFinding f : open) {
            byKind.merge(f.getKind().name(), 1L, Long::sum);
        }

        RevenueAuditSettings s = settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", s);
        out.put("open", open);
        out.put("recentlyClosed", findings.findTop300ByOrderByLastSeenAtDesc().stream()
                .filter(f -> f.getStatus() != RevenueFinding.Status.OPEN)
                .limit(50)
                .toList());
        out.put("highCount", open.stream().filter(f -> f.getSeverity() == RevenueFinding.Severity.HIGH).count());
        out.put("atRisk", atRisk);
        out.put("byKind", byKind);
        out.put("lastRunAt", s.getLastRunAt());
        return out;
    }

    /** Marks a finding dealt with; it re-opens if the sweep sees it again. */
    @Transactional
    public RevenueFinding resolve(Long id, String actor, String note) {
        return close(id, RevenueFinding.Status.RESOLVED, actor, note);
    }

    /**
     * Marks a finding as expected — a test login, a router account that is
     * meant to be there. Ignored findings stay ignored on later sweeps.
     */
    @Transactional
    public RevenueFinding ignore(Long id, String actor, String note) {
        return close(id, RevenueFinding.Status.IGNORED, actor, note);
    }

    private RevenueFinding close(Long id, RevenueFinding.Status status, String actor, String note) {
        RevenueFinding f = findings.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown finding: " + id));
        f.setStatus(status);
        f.setResolvedAt(Instant.now());
        f.setResolvedBy(actor == null || actor.isBlank() ? "system" : actor);
        f.setNote(trim(note, 300));
        audit.record(actor, "revenue.finding." + status.name().toLowerCase(),
                f.getKind() + " on " + f.getSubject() + (note == null || note.isBlank() ? "" : " — " + note));
        return findings.save(f);
    }

    // --- helpers ---

    private Set<String> ignoredAccounts(RevenueAuditSettings s) {
        Set<String> out = new HashSet<>();
        if (s.getIgnoredAccounts() == null) {
            return out;
        }
        for (String part : s.getIgnoredAccounts().split(",")) {
            String name = part.trim().toLowerCase();
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "KES 0" : String.format("KES %,.0f", amount);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
