package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
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
 * status, renew their home internet, resend their last code or reach support —
 * all in a chat, in English or Kiswahili, paying by M-Pesa. Reuses the same
 * plans, STK push, voucher issue and tickets the rest of the system uses; this
 * only drives the conversation. State is per-phone, in memory, short-lived.
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
    private final VoucherRepository vouchers;
    private final PortalSettingsService portalSettings;
    private final ReferralService referralService;

    private enum Step { MENU, PLAN, PAY_PHONE, RENEW_MONTHS, SUPPORT_MSG, REFERRAL_CODE }

    private static final class Session {
        Step step = Step.MENU;
        String lang = "EN";
        Long planId;
        Long subscriberId;
        List<Long> planIds = new ArrayList<>();
        Instant touched = Instant.now();
    }

    private static final Duration TTL = Duration.ofMinutes(20);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // EN / SW message pairs. %s / %d are filled per call.
    private static final Map<String, String[]> M = Map.ofEntries(
            Map.entry("menu", new String[]{
                    "👋 Welcome to *%s*!\n\nReply with a number:\n*1* — Buy WiFi\n*2* — My status\n*3* — Renew my internet\n*4* — Talk to support\n*5* — Resend my last code\n*6* — Refer a friend & earn\n\n_Reply *sw* for Kiswahili._",
                    "👋 Karibu *%s*!\n\nJibu na nambari:\n*1* — Nunua WiFi\n*2* — Hali yangu\n*3* — Ongeza intaneti\n*4* — Ongea na msaada\n*5* — Nitumie nambari yangu tena\n*6* — Mpe rafiki upate bonasi\n\n_Reply *en* for English._"}),
            Map.entry("refShare", new String[]{
                    "🎁 *Refer a friend & earn!*\nYour code: *%s*\nShare it — when a friend joins with it and makes their first purchase, you get *%d* free WiFi minutes and they get *%d*.\n\nHave a code from a friend? Reply with it now to claim, or *menu*.",
                    "🎁 *Mpe rafiki upate bonasi!*\nNambari yako: *%s*\nMtumie — rafiki akijiunga na kununua kwa mara ya kwanza, wewe utapata dakika *%d* za WiFi bure na yeye dakika *%d*.\n\nUna nambari kutoka kwa rafiki? Ijibu sasa, au *menu*."}),
            Map.entry("refOff", new String[]{
                    "The referral programme isn't available right now. Reply *menu*.",
                    "Programu ya rufaa haipatikani sasa. Jibu *menu*."}),
            Map.entry("refClaimOk", new String[]{
                    "✅ Referral code applied! You'll *both* get free WiFi minutes when you make your first purchase. Reply *1* to buy now, or *menu*.",
                    "✅ Nambari ya rufaa imewekwa! *Nyote* mtapata dakika za WiFi bure ukinunua kwa mara ya kwanza. Jibu *1* kununua, au *menu*."}),
            Map.entry("refClaimFail", new String[]{
                    "Sorry, that code couldn't be applied: %s\nReply *menu*.",
                    "Samahani, nambari hiyo haikubaliki: %s\nJibu *menu*."}),
            Map.entry("choose", new String[]{"*Choose a plan* — reply with its number:\n", "*Chagua kifurushi* — jibu na nambari yake:\n"}),
            Map.entry("back", new String[]{"\nReply *menu* to go back.", "\nJibu *menu* kurudi."}),
            Map.entry("payPrompt", new String[]{
                    "Reply with the *M-Pesa number* to pay from (2547XXXXXXXX), or reply *me* to use this number.",
                    "Jibu na *nambari ya M-Pesa* ya kulipa (2547XXXXXXXX), au jibu *me* kutumia hii."}),
            Map.entry("badPhone", new String[]{
                    "That doesn't look like a valid M-Pesa number. Reply as 2547XXXXXXXX, or *me*.",
                    "Nambari si sahihi. Jibu kama 2547XXXXXXXX, au *me*."}),
            Map.entry("buySent", new String[]{
                    "✅ M-Pesa request sent to %s for KES %s. Enter your PIN — your WiFi code arrives here once confirmed.",
                    "✅ Ombi la M-Pesa limetumwa kwa %s la KES %s. Weka PIN yako — nambari ya WiFi itakuja hapa baada ya kuthibitishwa."}),
            Map.entry("payFail", new String[]{
                    "Sorry, we couldn't start the M-Pesa payment right now. Please try again shortly.",
                    "Samahani, hatukuweza kuanzisha malipo sasa. Jaribu tena baadaye."}),
            Map.entry("statusNone", new String[]{
                    "We couldn't find an account on this number. Reply *1* to buy a WiFi voucher.",
                    "Hatukupata akaunti kwa nambari hii. Jibu *1* kununua WiFi."}),
            Map.entry("statusActive", new String[]{
                    "🟢 Your internet is *active* (paid until %s).\nReply *3* to renew, or *menu*.",
                    "🟢 Intaneti yako *inatumika* (imelipiwa hadi %s).\nJibu *3* kuongeza, au *menu*."}),
            Map.entry("statusInactive", new String[]{
                    "🔴 Your internet is *not active* (paid until %s).\nReply *3* to renew, or *menu*.",
                    "🔴 Intaneti yako *haitumiki* (ililipiwa hadi %s).\nJibu *3* kuongeza, au *menu*."}),
            Map.entry("renewMonths", new String[]{
                    "How many months would you like to pay for? Reply a number *1–12*.",
                    "Ungependa kulipia miezi mingapi? Jibu nambari *1–12*."}),
            Map.entry("renewBad", new String[]{"Reply a number of months between 1 and 12.", "Jibu nambari ya miezi kati ya 1 na 12."}),
            Map.entry("renewSent", new String[]{
                    "✅ M-Pesa request sent to renew for %d month(s). Enter your PIN — your internet stays on once confirmed.",
                    "✅ Ombi la M-Pesa limetumwa kuongeza miezi %d. Weka PIN yako — intaneti itaendelea baada ya kuthibitishwa."}),
            Map.entry("renewNone", new String[]{
                    "We couldn't find a home/office account on this number. Reply *1* to buy a WiFi voucher, or *menu*.",
                    "Hatukupata akaunti ya nyumbani/ofisini kwa nambari hii. Jibu *1* kununua WiFi, au *menu*."}),
            Map.entry("supportPrompt", new String[]{
                    "Type your question or issue in one message and we'll get back to you.",
                    "Andika swali au tatizo lako katika ujumbe mmoja, tutakujibu."}),
            Map.entry("supportDone", new String[]{
                    "🙌 Thanks — we've logged your request (ticket #%d). An agent will reach you shortly. Reply *menu*.",
                    "🙌 Asante — tumepokea ombi lako (tiketi #%d). Wakala atawasiliana nawe. Jibu *menu*."}),
            Map.entry("resendNone", new String[]{
                    "We couldn't find a voucher for this number. Reply *1* to buy one.",
                    "Hatukupata nambari ya WiFi kwa hii. Jibu *1* kununua."}),
            Map.entry("resendFound", new String[]{"🎟️ Your latest code is *%s* (%s).", "🎟️ Nambari yako ya hivi karibuni ni *%s* (%s)."}),
            Map.entry("planNo", new String[]{"Reply with a plan number from the list, or *menu* to go back.", "Jibu na nambari ya kifurushi, au *menu*."}),
            Map.entry("unknown", new String[]{"Sorry, I didn't get that.\n\n%s", "Samahani, sijaelewa.\n\n%s"}),
            Map.entry("langSet", new String[]{"Language set to English.", "Lugha imewekwa Kiswahili."})
    );

    private String t(Session s, String key, Object... args) {
        String[] v = M.get(key);
        String base = v == null ? key : v["SW".equals(s.lang) ? 1 : 0];
        return args.length == 0 ? base : String.format(base, args);
    }

    /** Entry point — needs the sender's number for the status/renew/resend paths. */
    public String replyWithPhone(String fromPhone, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        Session s = session(fromPhone);
        String lower = text.toLowerCase();

        if (lower.equals("sw") || lower.equals("kiswahili")) { s.lang = "SW"; return t(s, "langSet") + "\n\n" + menu(s); }
        if (lower.equals("en") || lower.equals("english")) { s.lang = "EN"; return t(s, "langSet") + "\n\n" + menu(s); }
        if (lower.isEmpty() || lower.matches("menu|hi|hello|start|0|hey|habari")) { s.step = Step.MENU; return menu(s); }

        if (s.step == Step.MENU) {
            switch (text) {
                case "2" -> { return statusFor(s, fromPhone); }
                case "3" -> {
                    Subscriber sub = subscribers.findByPhoneNumber(loose(fromPhone)).stream().findFirst().orElse(null);
                    if (sub == null) return t(s, "renewNone");
                    s.subscriberId = sub.getId();
                    s.step = Step.RENEW_MONTHS;
                    return t(s, "renewMonths");
                }
                case "5" -> { return resend(s, fromPhone); }
                case "6" -> { return referralEntry(s, fromPhone); }
                default -> { /* fall through to reply() */ }
            }
        }
        return reply(s, fromPhone, text);
    }

    private String reply(Session s, String fromPhone, String text) {
        return switch (s.step) {
            case MENU -> handleMenu(s, text);
            case PLAN -> handlePlan(s, text);
            case PAY_PHONE -> handleBuyPhone(s, fromPhone, text);
            case RENEW_MONTHS -> handleRenewMonths(s, text);
            case SUPPORT_MSG -> handleSupport(s, fromPhone, text);
            case REFERRAL_CODE -> handleReferralCode(s, fromPhone, text);
        };
    }

    private String handleMenu(Session s, String text) {
        if ("1".equals(text.trim())) {
            List<Plan> live = livePlans();
            if (live.isEmpty()) return "No plans are on sale right now. Please try again later.";
            s.planIds.clear();
            StringBuilder sb = new StringBuilder(t(s, "choose"));
            for (int i = 0; i < live.size(); i++) {
                Plan p = live.get(i);
                s.planIds.add(p.getId());
                sb.append(i + 1).append(") ").append(p.getName())
                        .append(" — KES ").append(p.getPrice().stripTrailingZeros().toPlainString()).append("\n");
            }
            s.step = Step.PLAN;
            return sb.append(t(s, "back")).toString();
        }
        if ("4".equals(text.trim())) { s.step = Step.SUPPORT_MSG; return t(s, "supportPrompt"); }
        return t(s, "unknown", menu(s));
    }

    private String handlePlan(Session s, String text) {
        Integer idx = asInt(text);
        if (idx == null || idx < 1 || idx > s.planIds.size()) return t(s, "planNo");
        s.planId = s.planIds.get(idx - 1);
        s.step = Step.PAY_PHONE;
        return t(s, "payPrompt");
    }

    private String handleBuyPhone(Session s, String fromPhone, String text) {
        String phone = "me".equalsIgnoreCase(text.trim()) ? normalize(fromPhone) : normalize(text);
        if (phone == null) return t(s, "badPhone");
        Plan p = plans.findById(s.planId).orElse(null);
        if (p == null) { reset(s); return t(s, "unknown", menu(s)); }
        try {
            payments.initiateStkPush(phone, s.planId);
        } catch (Exception e) {
            log.warn("WhatsApp buy STK failed for {}: {}", phone, e.getMessage());
            reset(s);
            return t(s, "payFail");
        }
        reset(s);
        return t(s, "buySent", phone, p.getPrice().stripTrailingZeros().toPlainString());
    }

    private String handleRenewMonths(Session s, String text) {
        Integer n = asInt(text);
        if (n == null || n < 1 || n > 12) return t(s, "renewBad");
        Long id = s.subscriberId;
        reset(s);
        if (id == null) return menu(s);
        try {
            subscriptions.initiateStk(id, n);
        } catch (Exception e) {
            log.warn("WhatsApp renew STK failed for subscriber {}: {}", id, e.getMessage());
            return t(s, "payFail");
        }
        return t(s, "renewSent", n);
    }

    private String handleSupport(Session s, String fromPhone, String text) {
        Subscriber sub = subscribers.findByPhoneNumber(loose(fromPhone)).stream().findFirst().orElse(null);
        SupportTicket ticket = tickets.save(SupportTicket.builder()
                .customerName(sub != null ? sub.getFullName() : "WhatsApp customer")
                .phoneNumber(fromPhone)
                .subject(text.length() > 120 ? text.substring(0, 120) : text)
                .priority(SupportTicket.Priority.MEDIUM)
                .status(SupportTicket.Status.OPEN)
                .createdBy("whatsapp")
                .build());
        reset(s);
        return t(s, "supportDone", ticket.getId());
    }

    /** Shows the customer's referral code + reward terms, and opens the code-entry step. */
    private String referralEntry(Session s, String fromPhone) {
        var rs = referralService.settings();
        if (!rs.isEnabled()) {
            s.step = Step.MENU;
            return t(s, "refOff");
        }
        var r = referralService.codeFor(loose(fromPhone));
        s.step = Step.REFERRAL_CODE;
        return t(s, "refShare", r.getCode(), rs.getReferrerMinutes(), rs.getRefereeMinutes());
    }

    /** A new customer replies with a friend's code to claim the referral bonus. */
    private String handleReferralCode(Session s, String fromPhone, String text) {
        reset(s);
        try {
            referralService.submitClaim(loose(fromPhone), text);
            return t(s, "refClaimOk");
        } catch (Exception e) {
            return t(s, "refClaimFail", e.getMessage());
        }
    }

    private String statusFor(Session s, String fromPhone) {
        Subscriber sub = subscribers.findByPhoneNumber(loose(fromPhone)).stream().findFirst().orElse(null);
        if (sub != null) {
            String until = sub.getPaidUntil() != null ? DATE.format(sub.getPaidUntil()) : "—";
            boolean active = sub.getStatus() == Subscriber.Status.ACTIVE
                    && sub.getPaidUntil() != null && sub.getPaidUntil().isAfter(Instant.now());
            return t(s, active ? "statusActive" : "statusInactive", until);
        }
        Voucher v = vouchers.findByPhoneNumberOrderByCreatedAtDesc(loose(fromPhone)).stream().findFirst().orElse(null);
        if (v != null) return t(s, "resendFound", v.getCode(), voucherState(v));
        return t(s, "statusNone");
    }

    private String resend(Session s, String fromPhone) {
        Voucher v = vouchers.findByPhoneNumberOrderByCreatedAtDesc(loose(fromPhone)).stream().findFirst().orElse(null);
        return v == null ? t(s, "resendNone") : t(s, "resendFound", v.getCode(), voucherState(v));
    }

    private String voucherState(Voucher v) {
        return switch (v.getStatus()) {
            case UNUSED -> "ready to use";
            case ACTIVE -> "in use";
            case EXPIRED -> "expired";
        };
    }

    private String menu(Session s) {
        String biz = portalSettings.settings().getBusinessName();
        if (biz == null || biz.isBlank()) biz = "our WiFi";
        return t(s, "menu", biz);
    }

    private List<Plan> livePlans() {
        return plans.findByActiveTrueOrderByPriceAsc().stream()
                // The pay-per-minute holder row is not a package anybody buys.
                .filter(p -> !CustomPlanService.SYSTEM_PLAN_NAME.equals(p.getName()))
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

    private static String normalize(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 10 && d.startsWith("0")) d = "254" + d.substring(1);
        if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) d = "254" + d;
        return d.matches("254\\d{9}") ? d : null;
    }

    private static String loose(String raw) {
        String n = normalize(raw);
        return n != null ? n : (raw == null ? "" : raw.replaceAll("\\D", ""));
    }
}
