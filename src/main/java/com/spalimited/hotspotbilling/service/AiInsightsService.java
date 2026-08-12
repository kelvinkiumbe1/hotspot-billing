package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The "ops copilot": turns live data into a short list of things worth acting
 * on today — customers about to lapse, recent drop-offs to win back, quiet
 * revenue — each with a one-tap next step. Deterministic (grounded in the real
 * numbers), so it never invents figures; the Groq assistant stays for free-form
 * questions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiInsightsService {

    private final SubscriberRepository subscribers;
    private final PaymentRepository payments;
    private final NotificationService notifications;
    private final PortalSettingsService portalSettings;

    private static final int LAPSING_DAYS = 3;
    private static final int WINBACK_DAYS = 14;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM").withZone(ZONE);

    /** key, severity, icon, title, detail, actionLabel, action(wired) or tab(link). */
    public record Insight(String key, String severity, String icon, String title,
                          String detail, String actionLabel, String action, String tab) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> insights() {
        Instant now = Instant.now();
        List<Subscriber> all = subscribers.findAll();

        List<Subscriber> lapsing = all.stream()
                .filter(s -> s.getStatus() == Subscriber.Status.ACTIVE && s.getPaidUntil() != null)
                .filter(s -> !s.getPaidUntil().isBefore(now)
                        && s.getPaidUntil().isBefore(now.plus(Duration.ofDays(LAPSING_DAYS))))
                .toList();

        long winback = all.stream()
                .filter(s -> s.getPaidUntil() != null)
                .filter(s -> s.getPaidUntil().isBefore(now)
                        && s.getPaidUntil().isAfter(now.minus(Duration.ofDays(WINBACK_DAYS))))
                .count();

        Instant startToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        BigDecimal todayRevenue = payments.findAll().stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCESS)
                .filter(p -> {
                    Instant at = p.getCompletedAt() != null ? p.getCompletedAt() : p.getCreatedAt();
                    return at != null && !at.isBefore(startToday);
                })
                .map(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Insight> out = new ArrayList<>();

        if (!lapsing.isEmpty()) {
            out.add(new Insight("lapsing", "high", "schedule",
                    lapsing.size() + " customer" + (lapsing.size() == 1 ? "" : "s") + " lapse in the next " + LAPSING_DAYS + " days",
                    "Their internet expires soon. A nudge now protects the renewal.",
                    "Remind them", "remind-lapsing", "subscribers"));
        }
        if (winback > 0) {
            out.add(new Insight("winback", "medium", "person_search",
                    winback + " recently lapsed",
                    "Expired in the last " + WINBACK_DAYS + " days — a good list to win back.",
                    "View customers", null, "subscribers"));
        }

        long active = all.stream().filter(s -> s.getStatus() == Subscriber.Status.ACTIVE).count();
        out.add(new Insight("revenue", "info", "payments",
                "KES " + todayRevenue.stripTrailingZeros().toPlainString() + " collected today",
                active + " active fixed-line customer" + (active == 1 ? "" : "s") + " on the books.",
                "Open analytics", null, "analytics"));

        String headline = out.stream().anyMatch(i -> i.severity().equals("high"))
                ? "A couple of things need a nudge today."
                : "You're on top of things — nothing urgent.";

        return Map.of("headline", headline, "insights", out, "generatedAt", now.toString());
    }

    /** The one-tap action for the lapsing insight: send each an expiry reminder. */
    @Transactional
    public int remindLapsing() {
        Instant now = Instant.now();
        List<Subscriber> lapsing = subscribers.findAll().stream()
                .filter(s -> s.getStatus() == Subscriber.Status.ACTIVE && s.getPaidUntil() != null)
                .filter(s -> !s.getPaidUntil().isBefore(now)
                        && s.getPaidUntil().isBefore(now.plus(Duration.ofDays(LAPSING_DAYS))))
                .toList();
        String biz = portalSettings.settings().getBusinessName();
        int sent = 0;
        for (Subscriber s : lapsing) {
            if (s.getPhoneNumber() == null || s.getPhoneNumber().isBlank()) continue;
            try {
                notifications.send(NotificationTemplate.Key.EXPIRY_REMINDER, s.getPhoneNumber(), Map.of(
                        "name", s.getFullName() == null ? "there" : s.getFullName(),
                        "business", biz == null ? "" : biz,
                        "date", DATE.format(s.getPaidUntil())));
                s.setRemindedForExpiry(s.getPaidUntil());
                subscribers.save(s);
                sent++;
            } catch (Exception e) {
                log.warn("Reminder to {} failed: {}", s.getPhoneNumber(), e.getMessage());
            }
        }
        return sent;
    }
}
