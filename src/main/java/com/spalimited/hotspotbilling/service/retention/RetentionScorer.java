package com.spalimited.hotspotbilling.service.retention;

import com.spalimited.hotspotbilling.domain.RetentionScore;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out how likely a customer is to leave, from signals already in the
 * database, and says which ones fired.
 *
 * <p>Deliberately a scorecard rather than a model. Not because a model would
 * be harder, but because the output has to be <em>actionable</em>: an operator
 * told "82% churn probability" can do nothing with it, while one told "hasn't
 * connected in 19 days and paid 6 days late last month" knows exactly what to
 * say when they pick up the phone. A transparent rule can also be argued with,
 * which matters when it is wrong about somebody's best customer.
 *
 * <p>Every weight below is a judgement, not a measurement. They are set so
 * that no single signal can reach the top band alone — one late payment is not
 * a leaving customer, and a scorecard that cried wolf on one would be ignored
 * within a fortnight.
 */
public final class RetentionScorer {

    private RetentionScorer() {
    }

    /** One thing that fired, with what it cost and how to say it. */
    public record Signal(int weight, String reason) {
    }

    /** The verdict for one customer. */
    public record Result(int score, RetentionScore.Band band, List<String> reasons, String action) {
    }

    // Nothing here can reach CRITICAL on its own; the bands need two or three
    // signals to agree, which is the difference between a warning and noise.
    private static final int SILENT_LINE = 30;
    private static final int SILENT_LINE_LONG = 45;
    private static final int USAGE_COLLAPSE = 25;
    private static final int LATE_PAYING = 20;
    private static final int LATE_WORSENING = 15;
    private static final int FAILED_PAYMENT = 25;
    private static final int SUSPENDED = 35;
    private static final int EXPIRING_UNPROMPTED = 15;
    private static final int NEW_AND_QUIET = 15;

    /**
     * Everything needed to score one customer, gathered by the caller so this
     * stays a pure function and can be tested without a database.
     */
    public record Input(Subscriber subscriber,
                        List<SubscriptionPayment> recentPayments,
                        long previousMonthMb,
                        int openTickets,
                        Instant now) {
    }

    public static Result score(Input input) {
        Subscriber sub = input.subscriber();
        Instant now = input.now();
        List<Signal> signals = new ArrayList<>();

        // --- Silence on the line ---
        // The strongest single signal there is. A customer who has stopped
        // using what they pay for has already left; they just have not
        // cancelled yet.
        //
        // Not counted for a suspended customer, though: they are not online
        // because we cut them off, so charging them for the silence as well
        // would be scoring our own action twice and would put the wrong
        // sentence at the top of the operator's list.
        Long daysQuiet = daysSince(sub.getLastSeenOnlineAt(), now);
        boolean suspended = sub.getStatus() == Subscriber.Status.SUSPENDED;
        if (!suspended && daysQuiet != null && daysQuiet >= 21) {
            signals.add(new Signal(SILENT_LINE_LONG,
                    "Not been online for " + daysQuiet + " days"));
        } else if (!suspended && daysQuiet != null && daysQuiet >= 10) {
            signals.add(new Signal(SILENT_LINE,
                    "Not been online for " + daysQuiet + " days"));
        }

        // --- Usage collapse ---
        // Judged against the customer's own history, never against an average
        // of everyone: a light user who is steady is not at risk, and a heavy
        // user who halves their usage is, at the same absolute number.
        long thisMonth = sub.getDataUsedMbOrZero();
        if (input.previousMonthMb() >= 500 && thisMonth * 4 < input.previousMonthMb()) {
            signals.add(new Signal(USAGE_COLLAPSE, "Usage down sharply — "
                    + thisMonth + "MB this month against " + input.previousMonthMb() + "MB last"));
        }

        // --- How they pay ---
        List<SubscriptionPayment> paid = input.recentPayments().stream()
                .filter(p -> p.getStatus() == SubscriptionPayment.Status.SUCCESS)
                .sorted(Comparator.comparing(SubscriptionPayment::getCompletedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        long failedRecently = input.recentPayments().stream()
                .filter(p -> p.getStatus() == SubscriptionPayment.Status.FAILED)
                .filter(p -> p.getCreatedAt() != null
                        && p.getCreatedAt().isAfter(now.minus(Duration.ofDays(45))))
                .count();
        if (failedRecently >= 2) {
            signals.add(new Signal(FAILED_PAYMENT,
                    failedRecently + " payments failed in the last six weeks"));
        }

        if (suspended) {
            signals.add(new Signal(SUSPENDED, "Suspended — the line is off right now"));
        }

        // --- Renewal slipping later ---
        // One late payment is a busy month. Three in a row, each later than
        // the last, is somebody deciding whether this is worth keeping.
        if (sub.getDunningAttempts() >= 2) {
            signals.add(new Signal(LATE_PAYING,
                    "Needed chasing " + sub.getDunningAttempts() + " times to pay"));
        }
        if (paid.size() >= 3 && slippingLater(paid)) {
            signals.add(new Signal(LATE_WORSENING, "Paying later each month"));
        }

        // --- About to lapse with no sign of renewing ---
        if (sub.getPaidUntil() != null && sub.getStatus() == Subscriber.Status.ACTIVE) {
            long daysLeft = Duration.between(now, sub.getPaidUntil()).toDays();
            boolean reminded = sub.getRemindedForExpiry() != null;
            if (daysLeft <= 3 && daysLeft >= 0 && reminded && daysQuiet != null && daysQuiet >= 5) {
                // Reminded, expiring, and not using it. Each alone is ordinary;
                // together they are somebody who has stopped caring.
                signals.add(new Signal(EXPIRING_UNPROMPTED,
                        "Expires in " + daysLeft + " day(s), reminded, and not online"));
            }
        }

        // --- New and never really started ---
        // The first month is when a connection either becomes a habit or does
        // not. A customer who barely used it is far more likely to go than a
        // three-year customer doing the same.
        Long age = daysSince(sub.getCreatedAt(), now);
        if (age != null && age <= 45 && thisMonth < 200 && (daysQuiet == null || daysQuiet >= 5)) {
            signals.add(new Signal(NEW_AND_QUIET, "New customer who has barely used the line"));
        }

        if (input.openTickets() >= 2) {
            signals.add(new Signal(LATE_WORSENING,
                    input.openTickets() + " support tickets still open"));
        }

        signals.sort(Comparator.comparingInt(Signal::weight).reversed());
        int total = Math.min(100, signals.stream().mapToInt(Signal::weight).sum());
        RetentionScore.Band band = bandFor(total);

        return new Result(total, band,
                signals.stream().map(Signal::reason).toList(),
                action(band, signals));
    }

    private static RetentionScore.Band bandFor(int score) {
        if (score >= 70) {
            return RetentionScore.Band.CRITICAL;
        }
        if (score >= 45) {
            return RetentionScore.Band.AT_RISK;
        }
        return score >= 25 ? RetentionScore.Band.WATCH : RetentionScore.Band.STEADY;
    }

    /**
     * What to actually do, matched to the strongest signal.
     *
     * <p>A generic "contact this customer" is worth nothing — the operator
     * knows that much from the score. What they need is which conversation to
     * have, because the one for a broken connection and the one for a customer
     * who cannot afford it are not the same conversation.
     */
    private static String action(RetentionScore.Band band, List<Signal> signals) {
        if (band == RetentionScore.Band.STEADY || signals.isEmpty()) {
            return null;
        }
        String worst = signals.get(0).reason();
        if (worst.startsWith("Not been online")) {
            return "Ring them — a line nobody uses is usually a line that stopped working";
        }
        if (worst.startsWith("Suspended")) {
            return "Reconnect first, ask about the bill second";
        }
        if (worst.contains("failed")) {
            return "Their payments keep failing — check the number on file is the one paying";
        }
        if (worst.startsWith("Usage down")) {
            return "Ask what changed — a working line they stopped using means something else did";
        }
        if (worst.startsWith("New customer")) {
            return "Check the install actually works — first-month silence is usually a fault";
        }
        return "Worth a call before the renewal date";
    }

    /**
     * Whether each renewal has come later than the one before.
     *
     * <p>Looks at the gap between payments rather than a due date, because the
     * due date moves with every renewal and would make an on-time customer
     * look late forever after one slow month.
     */
    private static boolean slippingLater(List<SubscriptionPayment> paid) {
        List<SubscriptionPayment> recent = paid.subList(Math.max(0, paid.size() - 4), paid.size());
        Long previousGap = null;
        int increases = 0;
        for (int i = 1; i < recent.size(); i++) {
            Instant a = recent.get(i - 1).getCompletedAt();
            Instant b = recent.get(i).getCompletedAt();
            if (a == null || b == null) {
                continue;
            }
            long gap = Duration.between(a, b).toDays();
            // A month covers 28 to 31 days, so only a gap meaningfully longer
            // than the last counts as slipping — otherwise February alone
            // would flag half the customer base.
            if (previousGap != null && gap > previousGap + 3) {
                increases++;
            }
            previousGap = gap;
        }
        return increases >= 2;
    }

    private static Long daysSince(Instant then, Instant now) {
        return then == null ? null : Math.max(0, Duration.between(then, now).toDays());
    }
}
