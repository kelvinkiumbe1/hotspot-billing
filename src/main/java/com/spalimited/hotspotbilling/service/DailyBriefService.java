package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CreditAdvance;
import com.spalimited.hotspotbilling.domain.HealthAlert;
import com.spalimited.hotspotbilling.domain.Incident;
import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.RevenueFinding;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.repository.CreditAdvanceRepository;
import com.spalimited.hotspotbilling.repository.HealthAlertRepository;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.RevenueFindingRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The owner's daily briefing.
 *
 * <p>This started as a sales total, which answers the one question the
 * operator could already answer by opening the dashboard. The questions they
 * could not answer without opening six screens are the ones that cost money:
 * who lapsed, what is expiring, which jobs nobody has taken, what the revenue
 * audit found overnight, whether the backup actually ran.
 *
 * <p>Two renderings of the same facts. The phone gets a scannable summary,
 * because it is read standing up; email gets the detail, because that is where
 * someone goes to act on it. Sections with nothing to say are left out
 * entirely — a briefing that always runs to twelve lines stops being read, and
 * silence about lapses should mean there were none.
 *
 * <p>Runs hourly, fires only in the chosen hour, and records the day it sent
 * so a restart cannot double-send.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyBriefService {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEEE d MMM").withZone(ZONE);
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("d MMM").withZone(ZONE);

    /** Customers this close to expiry are worth a mention while there is time to act. */
    private static final int EXPIRING_DAYS = 3;

    private final PaymentRepository payments;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final SubscriberRepository subscribers;
    private final SupportTicketRepository tickets;
    private final RevenueFindingRepository findings;
    private final HealthAlertRepository healthAlerts;
    private final IncidentRepository incidents;
    private final RouterRepository routers;
    private final CreditAdvanceRepository creditAdvances;
    private final OperatorAlertSettingsService alertSettings;
    private final MessagingSettingsService messagingSettings;
    private final EmailSettingsService emailSettings;
    private final SmsService smsService;
    private final EmailService emailService;
    private final PortalSettingsService portalSettings;
    private final MoneyService cash;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void maybeSend() {
        OperatorAlertSettings s = alertSettings.get();
        if (!s.isSalesDigestEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(ZONE);
        if (LocalTime.now(ZONE).getHour() != s.getSalesDigestHour() || today.equals(s.getLastDigestSent())) {
            return;
        }
        buildAndSend();
        alertSettings.markDigestSent(today);
    }

    /** Builds the briefing, sends it, and returns the short form for a preview. */
    @Transactional(readOnly = true)
    public String buildAndSend() {
        Brief brief = build();
        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, brief.shortForm());
        }
        String to = emailSettings.get().getFromAddress();
        if (emailService.isEnabled() && to != null && !to.isBlank()) {
            emailService.trySend(to, brief.subject(), brief.longForm());
        }
        log.info("Daily briefing sent");
        return brief.shortForm();
    }

    /** The briefing, in both renderings, so a preview costs no extra queries. */
    public record Brief(String subject, String shortForm, String longForm) {
    }

    @Transactional(readOnly = true)
    public Brief build() {
        Instant now = Instant.now();
        Instant startOfDay = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        String business = business();

        List<String> lines = new ArrayList<>();
        List<String> detail = new ArrayList<>();

        money(now, startOfDay, lines, detail);
        customers(now, startOfDay, lines, detail);
        support(lines, detail);
        atRisk(lines, detail);
        network(lines, detail);

        String heading = business + " — " + DAY.format(now);
        String shortForm = heading + "\n\n" + String.join("\n", lines);
        String longForm = heading + "\n\n" + String.join("\n", detail)
                + "\n\n— sent automatically by " + business + "'s system.";
        return new Brief(business + " — daily briefing", shortForm, longForm);
    }

    // --- Sections. Each appends a summary line and its own detail block. ---

    /**
     * Takings, against the same weekday a week ago. The comparison is the whole
     * point: "KES 12,000" means nothing on its own, "KES 12,000, down a third
     * on last Monday" is something to go and look into.
     */
    private void money(Instant now, Instant startOfDay, List<String> lines, List<String> detail) {
        BigDecimal hotspot = payments.sumAmountByStatusSince(Payment.Status.SUCCESS, startOfDay);
        long hotspotCount = payments.countByStatusAndCompletedAtAfter(Payment.Status.SUCCESS, startOfDay);
        BigDecimal subs = subscriptionPayments.sumAmountByStatusSince(
                SubscriptionPayment.Status.SUCCESS, startOfDay);
        long subsCount = subscriptionPayments.countByStatusAndCompletedAtAfter(
                SubscriptionPayment.Status.SUCCESS, startOfDay);
        BigDecimal total = hotspot.add(subs);

        // The same slice of the same weekday last week: comparing a part-day
        // against a whole one would report a collapse every morning.
        Instant lastWeekFrom = startOfDay.minus(Duration.ofDays(7));
        Instant lastWeekTo = now.minus(Duration.ofDays(7));
        BigDecimal lastWeek = payments
                .sumAmountByStatusBetween(Payment.Status.SUCCESS, lastWeekFrom, lastWeekTo)
                .add(subscriptionPayments.sumAmountByStatusBetween(
                        SubscriptionPayment.Status.SUCCESS, lastWeekFrom, lastWeekTo));

        lines.add("💰 " + cash.format(total) + " today (" + (hotspotCount + subsCount) + " payments)"
                + comparison(total, lastWeek));
        detail.add("MONEY IN");
        detail.add("  Today: " + cash.format(total) + " from " + (hotspotCount + subsCount) + " payment(s)");
        detail.add("    Hotspot: " + hotspotCount + " sale(s), " + cash.format(hotspot));
        detail.add("    Subscriptions: " + subsCount + " payment(s), " + cash.format(subs));
        detail.add("  Same time last " + DAY.format(lastWeekFrom).split(" ")[0] + ": " + cash.format(lastWeek));
        detail.add("");
    }

    private void customers(Instant now, Instant startOfDay, List<String> lines, List<String> detail) {
        List<Subscriber> all = subscribers.findAll();
        long joined = all.stream()
                .filter(s -> s.getCreatedAt() != null && !s.getCreatedAt().isBefore(startOfDay))
                .count();
        List<Subscriber> lapsedToday = all.stream()
                .filter(s -> s.getPaidUntil() != null)
                .filter(s -> !s.getPaidUntil().isBefore(startOfDay) && s.getPaidUntil().isBefore(now))
                .toList();
        List<Subscriber> expiring = all.stream()
                .filter(s -> s.getStatus() == Subscriber.Status.ACTIVE && s.getPaidUntil() != null)
                .filter(s -> !s.getPaidUntil().isBefore(now)
                        && s.getPaidUntil().isBefore(now.plus(Duration.ofDays(EXPIRING_DAYS))))
                .toList();

        if (joined == 0 && lapsedToday.isEmpty() && expiring.isEmpty()) {
            return;
        }
        List<String> bits = new ArrayList<>();
        if (joined > 0) {
            bits.add(joined + " new");
        }
        if (!lapsedToday.isEmpty()) {
            bits.add(lapsedToday.size() + " lapsed");
        }
        if (!expiring.isEmpty()) {
            bits.add(expiring.size() + " expiring in " + EXPIRING_DAYS + "d");
        }
        lines.add("👥 " + String.join(", ", bits));

        detail.add("CUSTOMERS");
        detail.add("  Joined today: " + joined);
        detail.add("  Lapsed today: " + lapsedToday.size());
        for (Subscriber s : lapsedToday.stream().limit(10).toList()) {
            detail.add("    • " + s.getFullName() + " (" + s.getPhoneNumber() + ")");
        }
        detail.add("  Expiring within " + EXPIRING_DAYS + " days: " + expiring.size());
        for (Subscriber s : expiring.stream().limit(10).toList()) {
            detail.add("    • " + s.getFullName() + " — " + SHORT_DATE.format(s.getPaidUntil()));
        }
        detail.add("");
    }

    private void support(List<String> lines, List<String> detail) {
        List<SupportTicket> live = tickets.findByStatusInOrderByCreatedAtAsc(
                List.of(SupportTicket.Status.OPEN, SupportTicket.Status.IN_PROGRESS));
        if (live.isEmpty()) {
            return;
        }
        long unclaimed = live.stream().filter(t -> t.getAssigneeIds().isEmpty()).count();
        lines.add("🎫 " + live.size() + " open ticket(s)"
                + (unclaimed == 0 ? "" : ", " + unclaimed + " nobody has taken"));

        detail.add("SUPPORT");
        detail.add("  Open: " + live.size() + " (" + unclaimed + " unassigned)");
        for (SupportTicket t : live.stream().limit(10).toList()) {
            detail.add("    • #" + t.getId() + " " + t.getPriority() + " — " + t.getSubject()
                    + " (" + t.getCustomerName() + ", waiting "
                    + FieldOpsService.describe(Duration.between(t.getCreatedAt(), Instant.now())) + ")");
        }
        detail.add("");
    }

    /** Money the system believes it is owed or has lost track of. */
    private void atRisk(List<String> lines, List<String> detail) {
        List<RevenueFinding> open = findings.findByStatus(RevenueFinding.Status.OPEN);
        List<CreditAdvance> outstanding =
                creditAdvances.findByStatusOrderByDueAtAsc(CreditAdvance.Status.OUTSTANDING);
        if (open.isEmpty() && outstanding.isEmpty()) {
            return;
        }
        BigDecimal findingTotal = open.stream()
                .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lentOut = outstanding.stream()
                .map(a -> a.getTotalDue() == null ? BigDecimal.ZERO : a.getTotalDue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> bits = new ArrayList<>();
        if (!open.isEmpty()) {
            bits.add(open.size() + " audit finding(s)"
                    + (findingTotal.signum() == 0 ? "" : " (" + cash.format(findingTotal) + ")"));
        }
        if (!outstanding.isEmpty()) {
            bits.add("" + cash.format(lentOut) + " on credit");
        }
        lines.add("⚠️ " + String.join(", ", bits));

        detail.add("MONEY AT RISK");
        for (RevenueFinding f : open.stream().limit(10).toList()) {
            detail.add("    • " + f.getKind() + " — " + f.getSubject()
                    + (f.getAmount() == null ? "" : " (" + cash.format(f.getAmount()) + ")"));
        }
        if (!outstanding.isEmpty()) {
            detail.add("  Out on credit: " + outstanding.size()
                    + " advance(s), " + cash.format(lentOut));
        }
        detail.add("");
    }

    /** The network and the system itself, including whether it can prove it backed up. */
    private void network(List<String> lines, List<String> detail) {
        List<Router> enabled = routers.findByEnabledTrue();
        long offline = enabled.stream().filter(r -> !r.isOnline()).count();
        List<Incident> openIncidents = incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN);
        List<HealthAlert> alerts = healthAlerts.findByStatus(HealthAlert.Status.OPEN);

        if (offline == 0 && openIncidents.isEmpty() && alerts.isEmpty()) {
            lines.add("🌐 All " + enabled.size() + " router(s) online, nothing else to report");
            detail.add("NETWORK & SYSTEM");
            detail.add("  All " + enabled.size() + " router(s) online. No open incidents or health alerts.");
            detail.add("");
            return;
        }

        List<String> bits = new ArrayList<>();
        if (offline > 0) {
            bits.add(offline + " of " + enabled.size() + " router(s) offline");
        }
        if (!openIncidents.isEmpty()) {
            bits.add(openIncidents.size() + " open outage(s)");
        }
        if (!alerts.isEmpty()) {
            bits.add(alerts.size() + " health alert(s)");
        }
        lines.add("🌐 " + String.join(", ", bits));

        detail.add("NETWORK & SYSTEM");
        for (Router r : enabled.stream().filter(x -> !x.isOnline()).limit(10).toList()) {
            detail.add("    • OFFLINE: " + r.getName());
        }
        for (Incident i : openIncidents.stream().limit(5).toList()) {
            detail.add("    • OUTAGE: " + i.getTitle() + ", running "
                    + FieldOpsService.describe(i.getDuration()));
        }
        for (HealthAlert a : alerts) {
            detail.add("    • " + a.getSeverity() + ": " + a.getTitle());
        }
        detail.add("");
    }

    // --- Formatting ---

    /**
     * The week-on-week move, and only when it is worth reading: a swing under a
     * tenth is noise, and a comparison against a week with no takings would be
     * a division by zero dressed up as a percentage.
     */
    private static String comparison(BigDecimal today, BigDecimal lastWeek) {
        if (lastWeek == null || lastWeek.signum() <= 0) {
            return "";
        }
        BigDecimal change = today.subtract(lastWeek)
                .multiply(BigDecimal.valueOf(100))
                .divide(lastWeek, 0, RoundingMode.HALF_UP);
        if (change.abs().intValue() < 10) {
            return " (about the same as last week)";
        }
        return change.signum() > 0
                ? " (↑ " + change.abs() + "% on last week)"
                : " (↓ " + change.abs() + "% on last week)";
    }

    private static String money(BigDecimal amount) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private String business() {
        String name = portalSettings.settings().getBusinessName();
        return name == null || name.isBlank() ? "Your business" : name;
    }
}
