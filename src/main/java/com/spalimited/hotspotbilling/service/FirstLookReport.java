package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.RevenueFinding;
import com.spalimited.hotspotbilling.repository.RevenueFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Revenue Guard found, said in a way an operator can act on and a
 * salesperson can put on a slide.
 *
 * <p>Every competitor bills. None of them tell an ISP how much money is walking
 * out of the building — a paid customer who never got their voucher, a receipt
 * used twice, a secret left on a router for somebody who stopped paying in March.
 * The checks already existed and ran nightly into a list of findings. What was
 * missing was the number: an operator evaluating Zidi on a Tuesday should be able
 * to run it once and be told what it is costing them, in their own currency, with
 * the reasoning shown.
 *
 * <p>Nothing here computes a new fact. It runs the sweep that already exists and
 * writes down what it means, because a list of nine finding kinds is a diagnostic
 * and this has to be an answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FirstLookReport {

    private final RevenueAuditService audit;
    private final RevenueFindingRepository findings;
    private final MoneyService money;

    /** What each kind means, and what an operator does about it. */
    private record Explains(String meaning, String action, boolean recoverable) {
    }

    /**
     * Plain English for each check.
     *
     * <p>{@code recoverable} separates money that can still be collected from
     * money that has already gone. Both matter, but only one of them is a job for
     * this afternoon, and lumping them into a single headline overstates the case
     * — which is the fastest way to lose an operator's trust on the first screen
     * they ever see.
     */
    private static final Map<RevenueFinding.Kind, Explains> MEANING = Map.ofEntries(
            Map.entry(RevenueFinding.Kind.PAID_NO_SERVICE, new Explains(
                    "Somebody paid and never got what they paid for.",
                    "Issue the pass or refund them, before they call.", false)),
            Map.entry(RevenueFinding.Kind.DUPLICATE_RECEIPT, new Explains(
                    "One payment receipt is behind two or more sales, so service was "
                            + "given away more than once for a single payment.",
                    "Check the duplicates and reverse the ones that were not paid for.", true)),
            Map.entry(RevenueFinding.Kind.UNAPPLIED_PAYMENT, new Explains(
                    "Money arrived and is still sitting unmatched.",
                    "Match it to a customer, or it stays as a complaint waiting to happen.", true)),
            Map.entry(RevenueFinding.Kind.SERVICE_NO_PAYMENT, new Explains(
                    "A pass exists that nobody paid for and no member of staff issued.",
                    "Find out who created it. This is the shape voucher fraud takes.", true)),
            Map.entry(RevenueFinding.Kind.GHOST_HOTSPOT_USER, new Explains(
                    "A user on the router matches no pass ever issued here.",
                    "Somebody has router access they should not have. Remove it and "
                            + "change the router password.", true)),
            Map.entry(RevenueFinding.Kind.GHOST_PPPOE_SECRET, new Explains(
                    "A PPPoE login on the router belongs to no customer in the system.",
                    "Either a customer was deleted without being disconnected, or "
                            + "somebody is being connected off the books.", true)),
            Map.entry(RevenueFinding.Kind.EXPIRED_STILL_ONLINE, new Explains(
                    "A session is still running on a pass that has already been used up.",
                    "They are online for free right now.", true)),
            Map.entry(RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, new Explains(
                    "A customer is past their paid-up date and was never cut off.",
                    "Every day this goes on is another day of free service.", true)),
            Map.entry(RevenueFinding.Kind.UNDERPAID, new Explains(
                    "A sale settled for less than the package costs.",
                    "Usually a discount nobody recorded, sometimes a price somebody "
                            + "edited.", true)));

    /** One kind of problem, counted and priced. */
    public record Line(String kind, String label, long count, BigDecimal amount,
                       String amountText, String meaning, String action,
                       boolean recoverable, String severity) {
    }

    /**
     * Runs the checks now and reports what they found.
     *
     * <p>Runs the sweep rather than reading yesterday's, because the whole point
     * is that an operator can ask the question and get today's answer.
     */
    @Transactional
    public Map<String, Object> run(String actor) {
        Map<String, Object> swept = audit.sweep(actor);
        List<RevenueFinding> open = findings.findByStatus(RevenueFinding.Status.OPEN);

        Map<RevenueFinding.Kind, List<RevenueFinding>> grouped = new LinkedHashMap<>();
        for (RevenueFinding f : open) {
            grouped.computeIfAbsent(f.getKind(), k -> new ArrayList<>()).add(f);
        }

        List<Line> lines = new ArrayList<>();
        BigDecimal recoverable = BigDecimal.ZERO;
        BigDecimal alreadyGone = BigDecimal.ZERO;

        for (Map.Entry<RevenueFinding.Kind, List<RevenueFinding>> entry : grouped.entrySet()) {
            RevenueFinding.Kind kind = entry.getKey();
            List<RevenueFinding> group = entry.getValue();
            BigDecimal total = group.stream()
                    .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Explains explains = MEANING.getOrDefault(kind,
                    new Explains("Something does not add up.", "Have a look.", true));
            if (explains.recoverable()) {
                recoverable = recoverable.add(total);
            } else {
                alreadyGone = alreadyGone.add(total);
            }
            RevenueFinding.Severity worst = group.stream()
                    .map(RevenueFinding::getSeverity)
                    .min(Comparator.comparingInt(Enum::ordinal))
                    .orElse(RevenueFinding.Severity.LOW);
            lines.add(new Line(kind.name(), humanise(kind), group.size(), total,
                    money.format(total), explains.meaning(), explains.action(),
                    explains.recoverable(), worst.name()));
        }

        // Biggest money first: an operator reads two lines of a report and the
        // two that matter most should be those two.
        lines.sort(Comparator.comparing(Line::amount).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranAt", Instant.now());
        out.put("findings", open.size());
        out.put("lines", lines);
        out.put("recoverable", recoverable);
        out.put("recoverableText", money.format(recoverable));
        out.put("alreadyGone", alreadyGone);
        out.put("alreadyGoneText", money.format(alreadyGone));
        out.put("total", recoverable.add(alreadyGone));
        out.put("totalText", money.format(recoverable.add(alreadyGone)));
        out.put("headline", headline(open.size(), recoverable.add(alreadyGone)));

        // Coverage, said out loud. A clean report from a sweep that could not
        // reach the routers is not a clean report, and letting somebody believe
        // it is would be the worst thing this screen could do.
        Object skipped = swept.get("skippedRouters");
        boolean routersChecked = Boolean.TRUE.equals(swept.get("routersChecked"));
        out.put("routersChecked", routersChecked);
        out.put("skippedRouters", skipped);
        out.put("coverage", coverage(routersChecked, skipped));
        return out;
    }

    private String headline(int count, BigDecimal total) {
        if (count == 0) {
            return "Nothing is leaking. Every payment matches a service and every service "
                    + "matches a payment.";
        }
        if (total.signum() == 0) {
            return count + " thing(s) need a look. None of them have a figure attached yet.";
        }
        return money.format(total) + " is unaccounted for across " + count + " finding(s).";
    }

    private static String coverage(boolean routersChecked, Object skipped) {
        if (!routersChecked) {
            return "The routers could not be read this time, so anything living only on a "
                    + "router — a login nobody pays for, a session on a spent pass — was not "
                    + "checked. This report covers the books only.";
        }
        if (skipped instanceof Number n && n.intValue() > 0) {
            return n + " router(s) could not be reached, so this report is complete for the "
                    + "books and incomplete for the network.";
        }
        return "Books and routers both checked.";
    }

    private static String humanise(RevenueFinding.Kind kind) {
        return switch (kind) {
            case PAID_NO_SERVICE -> "Paid, but got nothing";
            case DUPLICATE_RECEIPT -> "One receipt used twice";
            case UNAPPLIED_PAYMENT -> "Money nobody claimed";
            case SERVICE_NO_PAYMENT -> "Service nobody paid for";
            case GHOST_HOTSPOT_USER -> "Users on the router we never issued";
            case GHOST_PPPOE_SECRET -> "Logins on the router with no customer";
            case EXPIRED_STILL_ONLINE -> "Online after their time ran out";
            case LAPSED_NOT_SUSPENDED -> "Not paid up, not cut off";
            case UNDERPAID -> "Sold below the package price";
        };
    }
}
