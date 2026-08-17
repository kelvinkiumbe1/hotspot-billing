package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CommissionPayout;
import com.spalimited.hotspotbilling.domain.HealthAlert;
import com.spalimited.hotspotbilling.domain.Incident;
import com.spalimited.hotspotbilling.domain.OutboundMessage;
import com.spalimited.hotspotbilling.domain.RevenueFinding;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.repository.CommissionPayoutRepository;
import com.spalimited.hotspotbilling.repository.HealthAlertRepository;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.OutboundMessageRepository;
import com.spalimited.hotspotbilling.repository.RevenueFindingRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the assistant is told about the system it is answering for.
 *
 * <p>Until this existed the assistant was handed four numbers — today's sales,
 * voucher counts, subscriber counts, routers online — and nothing else. Asked
 * "is Lipa Baadaye switched on?" it had no way to know, and asked "what
 * happens when a payment fails?" it would invent a plausible answer rather
 * than describe the dunning schedule. The second failure is the dangerous one,
 * because a confident wrong answer about your own system is worse than no
 * answer at all.
 *
 * <p>Three sections. The primer is written by hand and says what this system
 * actually does; it lives next to the code so it can be corrected when the
 * code changes. The configuration and health sections are read live.
 *
 * <p><strong>Secrets never leave.</strong> This text is sent to a third party.
 * Nothing here reads a key, password, token or credential — only whether one
 * is present. A redaction pass runs over the finished text as a second line of
 * defence, on the assumption that someone will one day add a field here
 * without thinking about where it goes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemContextService {

    /**
     * Anything key-shaped, whoever put it there. Deliberately blunt: a mangled
     * sentence in the assistant's context costs nothing, a leaked credential
     * costs the operator their M-Pesa float.
     */
    private static final List<Pattern> SECRET_SHAPES = List.of(
            Pattern.compile("gsk_[A-Za-z0-9]{10,}"),
            Pattern.compile("sk-[A-Za-z0-9_-]{10,}"),
            Pattern.compile("\\b[A-Za-z0-9+/=_-]{32,}\\b"));

    private final HealthMonitorService health;
    private final BackupWatchService backups;
    private final HealthAlertRepository alerts;
    private final IncidentRepository incidents;
    private final RevenueFindingRepository findings;
    private final SupportTicketRepository tickets;
    private final CommissionPayoutRepository payouts;
    private final OutboundMessageRepository outbound;

    private final MessagingSettingsService messagingSettings;
    private final EmailService emailService;
    private final PaymentGatewayService gateways;
    private final MpesaService mpesa;
    private final OperatorAlertSettingsService alertSettings;
    private final FieldOpsService fieldOps;
    private final AgentPayoutService agentPayouts;
    private final OffPeakService offPeak;
    private final CapacityService capacity;
    private final CreditService credit;
    private final PaybillActivationService paybill;
    private final RevenueAuditService revenueAudit;
    private final AiSettingsService aiSettings;
    private final LoyaltyService loyalty;
    private final ReferralService referrals;
    private final PortalSettingsService portalSettings;
    private final MoneyService money;

    /** Everything the assistant should know, ready to paste into a prompt. */
    @Transactional(readOnly = true)
    public String forAssistant() {
        StringBuilder sb = new StringBuilder();
        // The primer is a text block, so the currency is substituted rather
        // than formatted — a stray %s in a prompt reads as a bug to the model.
        sb.append(primer().replace("%CURRENCY%", money.code())).append('\n');
        sb.append(configuration()).append('\n');
        sb.append(healthReport());
        return redact(sb.toString());
    }

    // --- What this system is ---

    /**
     * How the product actually works, in the operator's terms. Hand-written
     * because there is no way to derive "a failed renewal is retried on an
     * escalating schedule" from the schema, and a wrong answer to that
     * question sounds exactly like a right one.
     */
    private String primer() {
        return """
                HOW THIS SYSTEM WORKS
                It is billing and control for an ISP running two kinds of service:
                hotspot (prepaid vouchers, captive portal) and PPPoE subscribers (monthly
                home/office lines). Money is M-Pesa. Routers are MikroTik, driven over the
                RouterOS API. Amounts are in %CURRENCY%.

                HOW A CUSTOMER BUYS
                - Captive portal: picks a plan, gets an M-Pesa STK prompt, voucher arrives by SMS/WhatsApp.
                - Plain PayBill: pays by hand quoting a 6-character PayCode shown on the portal; the
                  pass issues itself. Below the cheapest plan they get a shortfall SMS; above the
                  ceiling it is left for a human.
                - USSD, for phones with no browser. Stateless menu.
                - WhatsApp bot: buy, check status, renew, resend a code, refer a friend, open a ticket.
                - Agents: resellers who hold voucher batches and earn commission on what is used.

                WHAT RUNS WITHOUT ANYONE ASKING
                - Invoicing and expiry: subscriptions are invoiced, reminded, auto-charged on expiry,
                  and suspended when unpaid.
                - Dunning: a failed auto-renewal is retried on an escalating schedule with a tap-to-pay
                  link, and cleared the moment they pay.
                - Win-back: a lapsed customer gets an escalating come-back series, stopping if they return.
                - Nudges: hotspot customers are warned before their time or data runs out.
                - Fair use: at the cap, a voucher is throttled, blocked or the customer notified.
                - Voucher sharing: one code live on several MACs at once alerts the operator.
                - Revenue audit (nightly): cross-checks money-in against service-out and router state,
                  writing findings that age in place and close themselves when fixed. An unreachable
                  router closes nothing — silence is not evidence.
                - Field jobs: technicians work jobs from WhatsApp; a job gone quiet gets its technician
                  a nudge, a job nobody took gets the operator one.
                - Ticket drafts: a suggested first reply is prepared from the customer's own account,
                  the network's state and what closed similar tickets. Never sent automatically.
                - Daily briefing: one message with money against last week, lapses, unclaimed jobs,
                  audit findings and network state.
                - Agent payouts: commission sent by M-Pesa B2C on a schedule. Only counted as paid when
                  Safaricom confirms it.
                - Off-peak offers: a discount across the genuinely quiet hours, worked out from traffic.
                - Capacity: each site's busy hour against its link, projected to weeks-until-full.
                - Health and backups: the system watches its own jobs, its callbacks and its backups,
                  and pings an external watchdog because a dead app cannot report itself.

                WHAT IT WILL NEVER DO ON ITS OWN
                Send a support reply, change a customer's package, order backhaul, or move money
                without either an explicit schedule the operator set or a person pressing a button.
                """;
    }

    // --- What is switched on ---

    private String configuration() {
        StringBuilder sb = new StringBuilder("CONFIGURATION (what is switched on right now)\n");

        String business = portalSettings.settings().getBusinessName();
        sb.append("- Business: ").append(business == null || business.isBlank() ? "unnamed" : business).append('\n');

        var msg = messagingSettings.settings();
        sb.append("- SMS: ").append(onOff(msg.isSmsEnabled()))
                .append(", provider ").append(msg.getSmsProvider())
                .append(", credentials ").append(present(msg.getSmsApiKey())).append('\n');
        sb.append("- WhatsApp: ").append(onOff(msg.isWhatsappEnabled()))
                .append(", credentials ").append(present(msg.getWhatsappAccessToken())).append('\n');
        sb.append("- Alert phone: ").append(present(msg.getAlertPhone()))
                .append(" (where operator alerts and the briefing go)\n");
        sb.append("- Email: ").append(onOff(emailService.isEnabled())).append('\n');

        sb.append("- M-Pesa collection (STK): ").append(yesNo(gateways.stkAvailable())).append('\n');
        sb.append("- M-Pesa payments out (B2C): ").append(yesNo(mpesa.canSendMoney()))
                .append(" — needed for agent commission payouts\n");
        sb.append("- Verify a pasted M-Pesa code: ").append(yesNo(gateways.transactionStatusAvailable())).append('\n');

        var alerts0 = alertSettings.get();
        sb.append("- Daily briefing: ").append(onOff(alerts0.isSalesDigestEnabled()))
                .append(alerts0.isSalesDigestEnabled() ? ", at " + alerts0.getSalesDigestHour() + ":00" : "")
                .append('\n');
        sb.append("- Router-down alerts: ").append(onOff(alerts0.isRouterOfflineAlert()))
                .append("; tell customers about outages: ").append(onOff(alerts0.isCustomerOutageNotice()))
                .append("; compensate for downtime: ").append(onOff(alerts0.isOutageCompensationEnabled()))
                .append("; public status page: ").append(onOff(alerts0.isStatusPageEnabled())).append('\n');

        var field = fieldOps.settings();
        sb.append("- Field jobs over WhatsApp: ").append(onOff(field.isWhatsappEnabled()))
                .append("; nudge a quiet job after ").append(field.getStaleJobHours())
                .append("h; flag an unclaimed job after ").append(field.getUnassignedAlertMinutes())
                .append("min; morning job list ").append(onOff(field.isDailySummaryEnabled()))
                .append("; tell the customer when a job closes ").append(onOff(field.isNotifyCustomerOnClose()))
                .append('\n');

        var ai = aiSettings.get();
        sb.append("- AI ticket-reply drafts: ").append(onOff(ai.isDraftTicketReplies()))
                .append(" (drafts only; a person always presses send)\n");

        var pay = agentPayouts.settings();
        sb.append("- Agent commission payouts: ").append(onOff(pay.isEnabled()))
                .append(", ").append(pay.getFrequency())
                .append(", auto-send ").append(onOff(pay.isAutoSend()))
                .append(", minimum ").append(money.format(pay.getMinimumAmount()))
                .append(", ceiling KES ").append(plain(pay.getMaxPerRun())).append('\n');

        var off = offPeak.settings();
        sb.append("- Off-peak night rate: ").append(onOff(off.isEnabled()))
                .append(", ").append(off.getDiscountPercent()).append("% off, window ")
                .append(off.isAutoWindow() ? "worked out from traffic"
                        : off.getWindowStartHour() + ":00–" + off.getWindowEndHour() + ":00")
                .append(", tell customers ").append(onOff(off.isNotify())).append('\n');

        var cap = capacity.settings();
        sb.append("- Capacity watching: ").append(onOff(cap.isEnabled()))
                .append(", full at ").append(cap.getCriticalPercent())
                .append("%, weekly text ").append(onOff(cap.isNotify())).append('\n');

        var cr = credit.settings();
        sb.append("- Pay later (Lipa Baadaye): ").append(onOff(cr.isEnabled()))
                .append(cr.isEnabled() ? ", up to " + money.format(cr.getMaxAdvance())
                        + " after " + cr.getMinPurchases() + " paid purchases" : "")
                .append('\n');

        var pb = paybill.settings();
        sb.append("- Zero-touch PayBill: ").append(onOff(pb.isEnabled()))
                .append(", auto-login by MAC ").append(onOff(pb.isAutoLoginByMac())).append('\n');

        var audit = revenueAudit.settings();
        sb.append("- Revenue audit: ").append(onOff(audit.isEnabled()))
                .append(", alert the operator ").append(onOff(audit.isAlertOperator())).append('\n');

        sb.append("- Loyalty points: ").append(onOff(loyalty.settings().isEnabled()))
                .append("; referrals: ").append(onOff(referrals.settings().isEnabled())).append('\n');

        var ops = backups.settings();
        sb.append("- Backup watching: ").append(onOff(ops.isBackupWatchEnabled()))
                .append(", expected every ").append(ops.getBackupExpectedHours())
                .append("h; self-watching: ").append(onOff(ops.isHealthWatchEnabled()))
                .append("; external watchdog ").append(present(ops.getHeartbeatUrl())).append('\n');

        return sb.toString();
    }

    // --- What looks wrong ---

    /**
     * The anomalies. Written so that a clean system produces short, definite
     * lines rather than nothing at all — "no open alerts" is an answer, and
     * an empty section would leave the model to guess whether it was told.
     */
    private String healthReport() {
        StringBuilder sb = new StringBuilder("WHAT LOOKS WRONG RIGHT NOW\n");
        Instant now = Instant.now();

        List<HealthAlert> open = alerts.findByStatus(HealthAlert.Status.OPEN);
        if (open.isEmpty()) {
            sb.append("- Health: no open alerts.\n");
        } else {
            sb.append("- Health: ").append(open.size()).append(" open alert(s):\n");
            open.forEach(a -> sb.append("    • ").append(a.getSeverity()).append(": ")
                    .append(a.getTitle()).append(" — ").append(a.getDetail()).append('\n'));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) health.overview().get("jobs");
        List<String> unwell = jobs == null ? List.of() : jobs.stream()
                .filter(j -> !"ok".equals(j.get("status")))
                .map(j -> j.get("label") + " (" + j.get("status") + ")")
                .toList();
        sb.append("- Scheduled jobs: ").append(unwell.isEmpty()
                ? "all running normally.\n" : "not healthy — " + String.join(", ", unwell) + ".\n");

        Map<String, Object> backup = backups.overview();
        sb.append("- Backups: ").append(Boolean.TRUE.equals(backup.get("healthy"))
                        ? "a good one arrived recently" : "NO recent good backup")
                .append(", last good ").append(backup.get("lastGoodAt") == null ? "never" : backup.get("lastGoodAt"))
                .append(", restore-tested ").append(yesNo(Boolean.TRUE.equals(backup.get("lastVerified"))))
                .append(", copied off-site ").append(yesNo(Boolean.TRUE.equals(backup.get("lastOffsite"))))
                .append('\n');

        List<Incident> outages = incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN);
        sb.append("- Outages: ").append(outages.isEmpty() ? "none open.\n"
                : outages.size() + " open — " + outages.get(0).getTitle() + ", running "
                        + FieldOpsService.describe(outages.get(0).getDuration()) + ".\n");

        List<RevenueFinding> openFindings = findings.findByStatus(RevenueFinding.Status.OPEN);
        if (openFindings.isEmpty()) {
            sb.append("- Revenue audit: nothing outstanding.\n");
        } else {
            BigDecimal total = openFindings.stream()
                    .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append("- Revenue audit: ").append(openFindings.size())
                    .append(" open finding(s) worth ").append(money.format(total)).append(". By kind: ")
                    .append(openFindings.stream()
                            .collect(java.util.stream.Collectors.groupingBy(
                                    f -> f.getKind().name(), java.util.stream.Collectors.counting()))
                            .entrySet().stream()
                            .map(e -> e.getKey() + " x" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append(".\n");
        }

        List<SupportTicket> live = tickets.findByStatusInOrderByCreatedAtAsc(
                List.of(SupportTicket.Status.OPEN, SupportTicket.Status.IN_PROGRESS));
        long unclaimed = live.stream().filter(t -> t.getAssigneeIds().isEmpty()).count();
        sb.append("- Support: ").append(live.size()).append(" open ticket(s), ")
                .append(unclaimed).append(" with nobody assigned");
        live.stream().findFirst().ifPresent(t -> sb.append("; oldest waiting ")
                .append(FieldOpsService.describe(Duration.between(t.getCreatedAt(), now))));
        sb.append(".\n");

        List<CommissionPayout> inFlight = payouts.findByStatusInOrderByCreatedAtAsc(
                List.of(CommissionPayout.Status.PENDING, CommissionPayout.Status.SENT));
        sb.append("- Agent payouts: ").append(inFlight.isEmpty() ? "none in flight.\n"
                : inFlight.size() + " in flight (" + inFlight.stream()
                        .filter(p -> p.getStatus() == CommissionPayout.Status.PENDING).count()
                        + " waiting to be released).\n");

        List<OutboundMessage> recent = outbound.findByCreatedAtAfter(now.minus(Duration.ofHours(1)));
        long failed = recent.stream().filter(m -> m.getStatus() == OutboundMessage.Status.FAILED).count();
        sb.append("- Messaging: ").append(recent.isEmpty() ? "nothing sent in the last hour.\n"
                : failed + " of " + recent.size() + " message(s) failed in the last hour.\n");

        return sb.toString();
    }

    // --- Plumbing ---

    /**
     * Last line of defence before this text leaves for a third party. Nothing
     * above should ever produce a credential; this is here because one day
     * somebody will add a field without thinking about where it goes.
     */
    static String redact(String text) {
        String out = text;
        for (Pattern p : SECRET_SHAPES) {
            out = p.matcher(out).replaceAll("[redacted]");
        }
        return out;
    }

    private static String onOff(boolean on) {
        return on ? "ON" : "off";
    }

    private static String yesNo(boolean yes) {
        return yes ? "yes" : "no";
    }

    /** Whether a secret or contact detail is set — never what it is. */
    private static String present(String value) {
        return value == null || value.isBlank() ? "not set" : "set";
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
