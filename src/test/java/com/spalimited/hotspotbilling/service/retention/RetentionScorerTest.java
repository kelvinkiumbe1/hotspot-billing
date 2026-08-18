package com.spalimited.hotspotbilling.service.retention;

import com.spalimited.hotspotbilling.domain.RetentionScore;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scorecard that decides who gets a phone call.
 *
 * <p>Two failure modes matter and they pull against each other. Score too
 * eagerly and the list is full of customers who were never going anywhere,
 * which teaches the operator to ignore it inside a fortnight. Score too
 * cautiously and it says nothing until the customer has already gone. The
 * tests below pin the line between them.
 */
class RetentionScorerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");

    private static Subscriber customer() {
        return Subscriber.builder()
                .id(1L)
                .fullName("Test Customer")
                .status(Subscriber.Status.ACTIVE)
                .createdAt(NOW.minus(400, ChronoUnit.DAYS))
                .lastSeenOnlineAt(NOW.minus(1, ChronoUnit.HOURS))
                .paidUntil(NOW.plus(20, ChronoUnit.DAYS))
                .dataUsedMb(5_000L)
                .build();
    }

    private static RetentionScorer.Result score(Subscriber sub) {
        return score(sub, List.of(), 0, 0);
    }

    private static RetentionScorer.Result score(Subscriber sub, List<SubscriptionPayment> payments,
                                                long previousMonthMb, int openTickets) {
        return RetentionScorer.score(new RetentionScorer.Input(
                sub, payments, previousMonthMb, openTickets, NOW));
    }

    @Test
    @DisplayName("A customer doing nothing wrong scores nothing and is left alone")
    void healthyCustomerIsSilent() {
        RetentionScorer.Result result = score(customer());

        assertThat(result.score()).isZero();
        assertThat(result.band()).isEqualTo(RetentionScore.Band.STEADY);
        assertThat(result.reasons()).isEmpty();
        // No action at all, rather than a vague one. A list where every row
        // says "worth a call" is a list nobody works through.
        assertThat(result.action()).isNull();
    }

    @Test
    @DisplayName("A line nobody has used for three weeks is the strongest signal there is")
    void longSilenceScoresHighest() {
        Subscriber sub = customer();
        sub.setLastSeenOnlineAt(NOW.minus(25, ChronoUnit.DAYS));

        RetentionScorer.Result result = score(sub);

        assertThat(result.score()).isEqualTo(45);
        assertThat(result.band()).isEqualTo(RetentionScore.Band.AT_RISK);
        assertThat(result.reasons().get(0)).contains("25 days");
        // The action names the likely cause rather than the symptom: a line
        // nobody uses is usually a line that stopped working.
        assertThat(result.action()).contains("stopped working");
    }

    @Test
    @DisplayName("No single signal can reach the top band on its own")
    void oneSignalIsNeverCritical() {
        for (Subscriber sub : List.of(
                silentFor(12), suspended(), needingChasing(3), collapsedUsage())) {
            RetentionScorer.Result result = sub == collapsedUsage()
                    ? score(sub, List.of(), 4_000, 0) : score(sub);
            assertThat(result.band())
                    .as("%s reached CRITICAL alone", result.reasons())
                    .isNotEqualTo(RetentionScore.Band.CRITICAL);
        }
    }

    @Test
    @DisplayName("Independent signals stacking up is what makes a customer critical")
    void severalSignalsTogetherAreCritical() {
        Subscriber sub = customer();
        sub.setStatus(Subscriber.Status.SUSPENDED);
        sub.setDunningAttempts(3);
        sub.setDataUsedMb(100L);

        RetentionScorer.Result result = score(sub, manyFailures(), 4_000, 0);

        assertThat(result.band()).isEqualTo(RetentionScore.Band.CRITICAL);
        assertThat(result.reasons()).hasSizeGreaterThanOrEqualTo(4);
        // Being cut off outranks everything else, because reconnecting them is
        // the first thing to do and asking about the bill is the second.
        assertThat(result.action()).contains("Reconnect first");
    }

    @Test
    @DisplayName("A suspended customer is not also blamed for being offline")
    void suspensionDoesNotDoubleCountSilence() {
        Subscriber sub = customer();
        sub.setStatus(Subscriber.Status.SUSPENDED);
        sub.setLastSeenOnlineAt(NOW.minus(30, ChronoUnit.DAYS));

        RetentionScorer.Result result = score(sub);

        // They are not online because we cut them off. Counting the silence as
        // a second, independent signal would score our own action twice — and
        // would put "not been online for 30 days" at the top of the operator's
        // list when the sentence they need is "this line is switched off".
        assertThat(result.reasons()).noneMatch(r -> r.startsWith("Not been online"));
        assertThat(result.reasons()).hasSize(1);
        assertThat(result.score()).isEqualTo(35);
        assertThat(result.action()).contains("Reconnect first");
    }

    @Test
    @DisplayName("Usage is judged against the customer's own history, not against everyone's")
    void usageCollapseIsRelative() {
        Subscriber heavy = customer();
        heavy.setDataUsedMb(400L);
        // Was 4 GB, now 400 MB — a tenth of what they used to do.
        assertThat(score(heavy, List.of(), 4_000, 0).reasons())
                .anyMatch(r -> r.startsWith("Usage down"));

        Subscriber light = customer();
        light.setDataUsedMb(400L);
        // The same 400 MB from someone who has always used 450 MB is a steady
        // customer, and flagging them would be exactly the noise to avoid.
        assertThat(score(light, List.of(), 450, 0).reasons())
                .noneMatch(r -> r.startsWith("Usage down"));
    }

    @Test
    @DisplayName("With no usage history at all, nothing is claimed about usage")
    void noHistoryMakesNoClaim() {
        Subscriber sub = customer();
        sub.setDataUsedMb(0L);

        // Zero last month means "we have no basis", not "they used nothing" —
        // the difference between a quiet report and flagging the entire book
        // on the day the feature ships.
        assertThat(score(sub, List.of(), 0, 0).reasons())
                .noneMatch(r -> r.startsWith("Usage down"));
    }

    @Test
    @DisplayName("A new customer who never got started is treated differently from a long-standing one")
    void newCustomerIsWatchedMoreClosely() {
        Subscriber fresh = customer();
        fresh.setCreatedAt(NOW.minus(20, ChronoUnit.DAYS));
        fresh.setDataUsedMb(50L);
        fresh.setLastSeenOnlineAt(NOW.minus(6, ChronoUnit.DAYS));

        RetentionScorer.Result result = score(fresh);

        assertThat(result.reasons()).anyMatch(r -> r.startsWith("New customer"));

        Subscriber established = customer();
        established.setDataUsedMb(50L);
        established.setLastSeenOnlineAt(NOW.minus(6, ChronoUnit.DAYS));
        // Same numbers, three years in — light use is just how they use it.
        assertThat(score(established).reasons())
                .noneMatch(r -> r.startsWith("New customer"));
    }

    @Test
    @DisplayName("One late month is not a leaving customer; three in a row is")
    void slippingLaterNeedsAPattern() {
        Subscriber sub = customer();

        // Steady: roughly a month between each payment.
        assertThat(score(sub, paidEvery(30, 30, 30), 0, 0).reasons())
                .noneMatch(r -> r.contains("later each month"));

        // Each gap meaningfully longer than the last.
        assertThat(score(sub, paidEvery(30, 38, 47), 0, 0).reasons())
                .anyMatch(r -> r.contains("later each month"));
    }

    @Test
    @DisplayName("February does not make an on-time customer look late")
    void shortMonthsAreTolerated() {
        Subscriber sub = customer();
        // 28, 31, 30 — an ordinary year, and a naive comparison would read the
        // 28-to-31 step as slipping.
        assertThat(score(sub, paidEvery(28, 31, 30), 0, 0).reasons())
                .noneMatch(r -> r.contains("later each month"));
    }

    @Test
    @DisplayName("A payment that keeps failing points at the number on file, not the customer")
    void repeatedFailuresNameTheLikelyCause() {
        Subscriber sub = customer();
        List<SubscriptionPayment> failures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            failures.add(SubscriptionPayment.builder()
                    .status(SubscriptionPayment.Status.FAILED)
                    .createdAt(NOW.minus(5L + i, ChronoUnit.DAYS))
                    .build());
        }

        RetentionScorer.Result result = score(sub, failures, 0, 0);

        assertThat(result.reasons()).anyMatch(r -> r.contains("payments failed"));
        assertThat(result.action()).contains("number on file");
    }

    @Test
    @DisplayName("Old failures do not haunt a customer who has since been paying")
    void staleFailuresAreForgotten() {
        Subscriber sub = customer();
        List<SubscriptionPayment> old = List.of(
                SubscriptionPayment.builder().status(SubscriptionPayment.Status.FAILED)
                        .createdAt(NOW.minus(120, ChronoUnit.DAYS)).build(),
                SubscriptionPayment.builder().status(SubscriptionPayment.Status.FAILED)
                        .createdAt(NOW.minus(200, ChronoUnit.DAYS)).build());

        assertThat(score(sub, old, 0, 0).reasons())
                .noneMatch(r -> r.contains("payments failed"));
    }

    @Test
    @DisplayName("The score is capped, so a very bad customer is still a readable number")
    void scoreIsCapped() {
        Subscriber sub = customer();
        sub.setLastSeenOnlineAt(NOW.minus(60, ChronoUnit.DAYS));
        sub.setStatus(Subscriber.Status.SUSPENDED);
        sub.setDunningAttempts(5);
        sub.setDataUsedMb(10L);
        sub.setCreatedAt(NOW.minus(30, ChronoUnit.DAYS));

        RetentionScorer.Result result = score(sub, manyFailures(), 5_000, 4);

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.band()).isEqualTo(RetentionScore.Band.CRITICAL);
    }

    @Test
    @DisplayName("Reasons come back worst first, so the top line is the one to act on")
    void reasonsAreOrdered() {
        Subscriber sub = customer();
        sub.setLastSeenOnlineAt(NOW.minus(30, ChronoUnit.DAYS));
        sub.setDunningAttempts(2);

        RetentionScorer.Result result = score(sub);

        assertThat(result.reasons().get(0)).startsWith("Not been online");
    }

    // --- fixtures ---

    private static Subscriber silentFor(int days) {
        Subscriber sub = customer();
        sub.setLastSeenOnlineAt(NOW.minus(days, ChronoUnit.DAYS));
        return sub;
    }

    private static Subscriber suspended() {
        Subscriber sub = customer();
        sub.setStatus(Subscriber.Status.SUSPENDED);
        return sub;
    }

    private static Subscriber needingChasing(int attempts) {
        Subscriber sub = customer();
        sub.setDunningAttempts(attempts);
        return sub;
    }

    private static Subscriber collapsedUsage() {
        Subscriber sub = customer();
        sub.setDataUsedMb(100L);
        return sub;
    }

    /** Successful payments with the given gaps, in days, between them. */
    private static List<SubscriptionPayment> paidEvery(int... gaps) {
        List<SubscriptionPayment> out = new ArrayList<>();
        Instant when = NOW.minus(200, ChronoUnit.DAYS);
        out.add(SubscriptionPayment.builder()
                .status(SubscriptionPayment.Status.SUCCESS).completedAt(when).build());
        for (int gap : gaps) {
            when = when.plus(gap, ChronoUnit.DAYS);
            out.add(SubscriptionPayment.builder()
                    .status(SubscriptionPayment.Status.SUCCESS).completedAt(when).build());
        }
        return out;
    }

    private static List<SubscriptionPayment> manyFailures() {
        List<SubscriptionPayment> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(SubscriptionPayment.builder()
                    .status(SubscriptionPayment.Status.FAILED)
                    .createdAt(NOW.minus(3L + i, ChronoUnit.DAYS)).build());
        }
        return out;
    }
}
