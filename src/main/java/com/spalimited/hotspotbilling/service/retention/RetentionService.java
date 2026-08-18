package com.spalimited.hotspotbilling.service.retention;

import com.spalimited.hotspotbilling.domain.RetentionScore;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.repository.RetentionScoreRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores every customer for how likely they are to leave, and keeps the answer
 * where an operator will see it.
 *
 * <p>Runs nightly rather than on demand: the signals it reads move over days,
 * and recomputing on every page load would spend a lot of database time to
 * produce the same number.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionService {

    private final SubscriberRepository subscribers;
    private final SubscriptionPaymentRepository payments;
    private final SupportTicketRepository tickets;
    private final RetentionScoreRepository scores;
    private final com.spalimited.hotspotbilling.repository.RadiusSessionRepository sessions;

    @Transactional
    public int scoreAll() {
        Instant now = Instant.now();
        int scored = 0;
        // Loaded once rather than per customer: on a base of two thousand
        // subscribers this is one query instead of two thousand.
        Map<String, Integer> openTicketsByPhone = openTicketCounts();

        for (Subscriber sub : subscribers.findAll()) {
            try {
                RetentionScorer.Result result = RetentionScorer.score(new RetentionScorer.Input(
                        sub,
                        payments.findBySubscriberIdOrderByCreatedAtDesc(sub.getId()),
                        previousMonthUsage(sub),
                        openTicketsByPhone.getOrDefault(sub.getPhoneNumber(), 0),
                        now));

                RetentionScore row = scores.findBySubscriberId(sub.getId())
                        .orElseGet(() -> RetentionScore.builder().subscriberId(sub.getId()).build());
                if (row.getId() != null) {
                    row.setPreviousScore(row.getScore());
                    // A customer who has climbed back into trouble deserves to
                    // be looked at again, so an old acknowledgement is cleared
                    // rather than left hiding a fresh problem.
                    if (result.score() - row.getScore() >= 20) {
                        row.setAcknowledgedAt(null);
                        row.setAcknowledgedBy(null);
                    }
                }
                row.setScore(result.score());
                row.setBand(result.band());
                row.setReasons(String.join(" · ", result.reasons()));
                row.setSuggestedAction(result.action());
                row.setScoredAt(now);
                scores.save(row);
                scored++;
            } catch (Exception e) {
                log.warn("Could not score subscriber {}: {}", sub.getId(), e.getMessage());
            }
        }
        log.info("Retention: scored {} customers", scored);
        return scored;
    }

    /**
     * Last month's data usage, in MB.
     *
     * <p>The subscriber's own counter is reset monthly and the previous value
     * is not kept, so this reads the session history instead — which exists
     * only where RADIUS is switched on. Where it is not, this returns zero, and
     * the scorer treats zero as "no basis to compare" rather than "they used
     * nothing". That distinction is the whole reason it is written this way:
     * the second reading would flag every customer on the day this ships.
     */
    private long previousMonthUsage(Subscriber sub) {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime startOfThisMonth = now.withDayOfMonth(1).toLocalDate()
                    .atStartOfDay(now.getZone());
            ZonedDateTime startOfLastMonth = startOfThisMonth.minusMonths(1);
            long octets = sessions.totalOctetsBetween(sub.getId(),
                    startOfLastMonth.toInstant(), startOfThisMonth.toInstant());
            return octets / 1_048_576L;
        } catch (Exception e) {
            log.debug("No usage history for subscriber {}: {}", sub.getId(), e.getMessage());
            return 0;
        }
    }

    private Map<String, Integer> openTicketCounts() {
        Map<String, Integer> byPhone = new LinkedHashMap<>();
        for (SupportTicket ticket : tickets.findByStatusInOrderByCreatedAtAsc(
                List.of(SupportTicket.Status.OPEN, SupportTicket.Status.IN_PROGRESS))) {
            String phone = ticket.getPhoneNumber();
            if (phone != null && !phone.isBlank()) {
                byPhone.merge(phone, 1, Integer::sum);
            }
        }
        return byPhone;
    }

    /** The customers worth ringing, worst first. */
    @Transactional(readOnly = true)
    public List<RetentionScore> atRisk() {
        return scores.findByBandInOrderByScoreDesc(List.of(
                RetentionScore.Band.CRITICAL, RetentionScore.Band.AT_RISK, RetentionScore.Band.WATCH));
    }

    @Transactional
    public RetentionScore acknowledge(Long subscriberId, String who) {
        RetentionScore row = scores.findBySubscriberId(subscriberId).orElseThrow(() ->
                new IllegalArgumentException("That customer has not been scored yet"));
        row.setAcknowledgedAt(Instant.now());
        row.setAcknowledgedBy(who);
        return scores.save(row);
    }

    /** How the book looks overall, for the dashboard. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RetentionScore.Band band : RetentionScore.Band.values()) {
            out.put(band.name().toLowerCase(), scores.countByBand(band));
        }
        out.put("scoredAt", scores.findTopByOrderByScoredAtDesc()
                .map(RetentionScore::getScoredAt).orElse(null));
        return out;
    }
}
