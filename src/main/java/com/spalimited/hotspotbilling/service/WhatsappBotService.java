package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A self-service WhatsApp assistant: customers buy a voucher, check their
 * status, renew their home internet or reach support — all in a chat, paying
 * by M-Pesa. Reuses the same plans, STK push, voucher issue and tickets the
 * rest of the system uses; this only drives the conversation.
 *
 * <p>Conversation state is kept in memory per phone with a short TTL — a
 * customer chat is brief and a restart simply drops them back to the menu.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsappBotService {

    private final PlanRepository plans;
    private final PaymentService payments;
    private final SubscriberRepository subscribers;
    private final SubscriptionService subscriptions;
    private final SupportTicketRepository tickets;
    private final PortalSettingsService portalSettings;

    private enum Step { MENU, PLAN, PAY_PHONE, RENEW_CONFIRM, SUPPORT_MSG }

    private static final class Session {
        Step step = Step.MENU;
        Long planId;
        Long subscriberId;
        List<Long> planIds = new ArrayList<>();
        Instant touched = Instant.now();
    }

    private static final Duration TTL = Duration.ofMinutes(20);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Handle one inbound message and return the reply to send back. */
    public String reply(String fromPhone, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        Session s = session(fromPhone);
        String lower = text.toLowerCase();

        // Universal escapes back to the menu.
        if (lower.isEmpty() || lower.matches("menu|hi|hello|start|0|hey|habari")) {
            s.step = Step.MENU;
            return menu();
        }

        return switch (s.step) {
            case MENU -> handleMenu(s, text);
            case PLAN -> handlePlanChoice(s, text);
            case PAY_PHONE -> handleBuyPhone(s, fromPhone, text);
            case RENEW_CONFIRM -> handleRenewConfirm(s, fromPhone, lower);
            case SUPPORT_MSG -> handleSupport(s, fromPhone, text);
        };
    }

    private String handleMenu(Session s, String text) {
        switch (text.trim()) {
            case "1" -> {
                List<Plan> live = livePlans();
                if (live.isEmpty()) return "No plans are on sale right now. Please try again later.";
                s.planIds.clear();
                StringBuilder sb = new StringBuilder("*Choose a plan* — reply with its number:\n");
                for (int i = 0; i < live.size(); i++) {
                    Plan p = live.get(i);
                    s.planIds.add(p.getId());
                    sb.append(i + 1).append(") ").append(p.getName())
                            .append(" — KES ").append(p.getPrice().stripTrailingZeros().toPlainString())
                            .append("\n");
                }
                s.step = Step.PLAN;
                return sb.append("\nReply *menu* to go back.").toString();
            }
            case "4" -> {
                s.step = Step.SUPPORT_MSG;
                return "Type your question or issue in one message and we'll get back to you.";
            }
            default -> {
                return "Sorry, I didn't get that.\n\n" + menu();
            }
        }
    }

    private String handlePlanChoice(Session s, String text) {
        Integer idx = asInt(text);
        if (idx == null || idx < 1 || idx > s.planIds.size()) {
            return "Reply with a plan number from the list, or *menu* to go back.";
        }
        s.planId = s.planIds.get(idx - 1);
        s.step = Step.PAY_PHONE;
        Plan p = plans.findById(s.planId).orElse(null);
        String price = p == null ? "" : " (KES " + p.getPrice().stripTrailingZeros().toPlainString() + ")";
        return "Great" + price + ". Reply with the *M-Pesa number* to pay from (2547XXXXXXXX), "
                + "or reply *me* to use this number.";
    }

    private String handleBuyPhone(Session s, String fromPhone, String text) {
        String phone = "me".equalsIgnoreCase(text.trim()) ? normalize(fromPhone) : normalize(text);
        if (phone == null) {
            return "That doesn't look like a valid M-Pesa number. Reply as 2547XXXXXXXX, or *me*.";
        }
        Plan p = plans.findById(s.planId).orElse(null);
        if (p == null) { reset(s); return "That plan is no longer available. Reply *menu*."; }
        try {
            payments.initiateStkPush(phone, s.planId);
        } catch (Exception e) {
            log.warn("WhatsApp buy STK failed for {}: {}", phone, e.getMessage());
            reset(s);
            return "Sorry, we couldn't start the M-Pesa payment right now. Please try again shortly.";
        }
        reset(s);
        return "✅ M-Pesa request sent to " + phone + " for KES "
                + p.getPrice().stripTrailingZeros().toPlainString()
                + ". Enter your PIN to pay — your WiFi code arrives by SMS/WhatsApp once it's confirmed.";
    }

    private String handleRenewConfirm(Session s, String fromPhone, String lower) {
        if (!lower.startsWith("y")) { reset(s); return "No problem — nothing was charged. Reply *menu*."; }
        Long id = s.subscriberId;
        reset(s);
        if (id == null) return menu();
        try {
            subscriptions.initiateStk(id, 1);
        } catch (Exception e) {
            log.warn("WhatsApp renew STK failed for subscriber {}: {}", id, e.getMessage());
            return "Sorry, we couldn't start the renewal payment right now. Please try again shortly.";
        }
        return "✅ M-Pesa request sent. Enter your PIN to renew for 1 month. "
                + "Your internet stays on once payment is confirmed.";
    }

    private String handleSupport(Session s, String fromPhone, String text) {
        Subscriber sub = subscribers.findByPhoneNumber(normalizeLoose(fromPhone)).stream().findFirst().orElse(null);
        SupportTicket t = tickets.save(SupportTicket.builder()
                .customerName(sub != null ? sub.getFullName() : "WhatsApp customer")
                .phoneNumber(fromPhone)
                .subject(text.length() > 120 ? text.substring(0, 120) : text)
                .priority(SupportTicket.Priority.MEDIUM)
                .status(SupportTicket.Status.OPEN)
                .createdBy("whatsapp")
                .build());
        reset(s);
        return "🙌 Thanks — we've logged your request (ticket #" + t.getId()
                + "). An agent will get back to you shortly. Reply *menu* for anything else.";
    }

    /** Status/renew both need a per-phone lookup; done here so we know the sender. */
    public String replyWithPhone(String fromPhone, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        Session s = session(fromPhone);
        // Intercept the two options that need the caller's own number.
        if (s.step == Step.MENU && "2".equals(text)) {
            return statusFor(fromPhone);
        }
        if (s.step == Step.MENU && "3".equals(text)) {
            List<Subscriber> subs = subscribers.findByPhoneNumber(normalizeLoose(fromPhone));
            Subscriber sub = subs.stream().findFirst().orElse(null);
            if (sub == null) {
                return "We couldn't find a home/office account on this number. Reply *1* to buy a WiFi voucher, or *menu*.";
            }
            s.subscriberId = sub.getId();
            s.step = Step.RENEW_CONFIRM;
            return "Renew *" + sub.getFullName() + "*'s internet for 1 month at KES "
                    + sub.getMonthlyFee().stripTrailingZeros().toPlainString()
                    + "? Reply *YES* to get an M-Pesa prompt, or *menu* to cancel.";
        }
        return reply(fromPhone, rawText);
    }

    private String statusFor(String fromPhone) {
        if (fromPhone == null) return "";
        Subscriber sub = subscribers.findByPhoneNumber(normalizeLoose(fromPhone)).stream().findFirst().orElse(null);
        if (sub == null) {
            return "We couldn't find an account on this number. Reply *1* to buy a WiFi voucher.";
        }
        String until = sub.getPaidUntil() != null ? DATE.format(sub.getPaidUntil()) : "—";
        boolean active = sub.getStatus() == Subscriber.Status.ACTIVE
                && sub.getPaidUntil() != null && sub.getPaidUntil().isAfter(Instant.now());
        return (active ? "🟢 Your internet is *active*" : "🔴 Your internet is *not active*")
                + " (paid until " + until + ").\nReply *3* to renew, or *menu* for options.";
    }

    private String menu() {
        String biz = portalSettings.settings().getBusinessName();
        if (biz == null || biz.isBlank()) biz = "our WiFi";
        return "👋 Welcome to *" + biz + "*!\n\nReply with a number:\n"
                + "*1* — Buy WiFi\n"
                + "*2* — My internet status\n"
                + "*3* — Renew my internet\n"
                + "*4* — Talk to support";
    }

    private List<Plan> livePlans() {
        return plans.findByActiveTrueOrderByPriceAsc().stream()
                .filter(p -> p.getType() == Plan.Type.HOTSPOT)
                .filter(p -> p.getAvailability() == null || p.getAvailability() == Plan.Availability.LIVE)
                .toList();
    }

    private Session session(String phone) {
        Session s = sessions.compute(phone, (k, v) -> {
            if (v == null || Instant.now().isAfter(v.touched.plus(TTL))) return new Session();
            return v;
        });
        s.touched = Instant.now();
        return s;
    }

    private void reset(Session s) {
        s.step = Step.MENU;
        s.planId = null;
        s.subscriberId = null;
        s.planIds.clear();
    }

    private static Integer asInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    /** Normalise to strict 2547XXXXXXXX, or null if it can't be. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 10 && d.startsWith("0")) d = "254" + d.substring(1);
        if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) d = "254" + d;
        return d.matches("254\\d{9}") ? d : null;
    }

    /** Best-effort digits for a lookup (stored numbers may vary slightly). */
    private static String normalizeLoose(String raw) {
        String n = normalize(raw);
        return n != null ? n : (raw == null ? "" : raw.replaceAll("\\D", ""));
    }
}
