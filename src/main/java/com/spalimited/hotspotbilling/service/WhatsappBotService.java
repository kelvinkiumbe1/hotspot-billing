package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CustomPlanSettings;
import com.spalimited.hotspotbilling.domain.Lead;
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
    private final VoucherService voucherService;
    private final CustomPlanService customPlanService;
    private final FieldOpsService fieldOps;
    private final com.spalimited.hotspotbilling.repository.LeadRepository leads;
    private final MoneyService money;
    private final com.spalimited.hotspotbilling.service.i18n.Messages messages;

    private enum Step {
        MENU, PLAN, PAY_PHONE, RENEW_MONTHS, SUPPORT_MSG, REFERRAL_CODE, PASS,
        /** Pay-per-minute: they type how long they want. */
        CUSTOM_MINUTES,
        /** Asking for a line at home or the office. */
        LEAD_NAME, LEAD_LOCATION, LEAD_PACKAGE, LEAD_WHEN
    }

    /** When a customer would rather be called, offered as a short list. */
    private static final String[] CALLBACK_SLOTS = {
            "Morning (8am – 12pm)", "Afternoon (12pm – 5pm)", "Evening (5pm – 8pm)", "Any time"
    };

    /** Marks the "choose your own time" row in a plan list. */
    private static final long CUSTOM_PLAN_CHOICE = -1L;

    private static final class Session {
        Step step = Step.MENU;
        String lang = "EN";
        Long planId;
        Long subscriberId;
        /** The hotspot pass being looked at, for the sign-out and reissue actions. */
        Long voucherId;
        Integer customMinutes;
        String leadName;
        String leadLocation;
        String leadPackage;
        List<Long> leadPlanIds = new ArrayList<>();
        List<Long> planIds = new ArrayList<>();
        Instant touched = Instant.now();
    }

    private static final Duration TTL = Duration.ofMinutes(20);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final com.spalimited.hotspotbilling.service.i18n.PhoneNumbers phones;

    // EN / SW message pairs. %s / %d are filled per call.
    private static final Map<String, String[]> M = Map.ofEntries(
            Map.entry("menu", new String[]{
                    "👋 Welcome to *%s*!\n\nReply with a number:\n*1* — Buy WiFi\n*2* — Time & data left\n*3* — Renew my internet\n*4* — Talk to support\n*5* — Resend my last code\n*6* — Refer a friend & earn\n*7* — WiFi at my home or office\n\n_Reply *sw* for Kiswahili._",
                    "👋 Karibu *%s*!\n\nJibu na nambari:\n*1* — Nunua WiFi\n*2* — Muda na data iliyobaki\n*3* — Ongeza intaneti\n*4* — Ongea na msaada\n*5* — Nitumie nambari yangu tena\n*6* — Mpe rafiki upate bonasi\n*7* — WiFi nyumbani au ofisini\n\n_Reply *en* for English._"}),
            Map.entry("customAsk", new String[]{
                    "How many minutes do you need? Reply with a number between *%d* and *%d*.\nIt works out at about %s an hour.",
                    "Unahitaji dakika ngapi? Jibu nambari kati ya *%d* na *%d*.\nNi karibu %s kwa saa."}),
            Map.entry("customBad", new String[]{
                    "Reply with a number of minutes between %d and %d.",
                    "Jibu nambari ya dakika kati ya %d na %d."}),
            Map.entry("customPrice", new String[]{
                    "%d minutes costs *%s*.\n\n",
                    "Dakika %d ni *%s*.\n\n"}),
            Map.entry("leadName", new String[]{
                    "🏠 Great — let's get you connected at home or the office.\n\nWhat's your full name?",
                    "🏠 Vizuri — tukuunganishe nyumbani au ofisini.\n\nJina lako kamili ni nani?"}),
            Map.entry("leadWhere", new String[]{
                    "Thanks %s. Where should we come to? Give the estate, street or a nearby landmark.",
                    "Asante %s. Tuje wapi? Taja mtaa, barabara au alama iliyo karibu."}),
            Map.entry("leadPackageList", new String[]{
                    "Which package are you after? Reply with its number:\n",
                    "Unahitaji kifurushi kipi? Jibu na nambari yake:\n"}),
            Map.entry("leadPackageAdvise", new String[]{
                    "\nNot sure? Reply *advise* and we'll recommend one when we call.",
                    "\nHujui? Jibu *advise* tukupendekeze tukikupigia."}),
            Map.entry("leadPackageAgain", new String[]{
                    "Reply with a number from 1 to %d, or *advise* if you're not sure.",
                    "Jibu nambari kutoka 1 hadi %d, au *advise* kama hujui."}),
            Map.entry("leadPackageOpen", new String[]{
                    "What speed do you need? Something like *10 Mbps*, or how you'd use it — "
                            + "\"streaming for four people\".\n\nNot sure? Reply *advise*.",
                    "Unahitaji spidi gani? Kama *10 Mbps*, au utaitumiaje — "
                            + "\"kutazama video watu wanne\".\n\nHujui? Jibu *advise*."}),
            Map.entry("leadWhen", new String[]{
                    "When suits you for a call? Reply with a number, or tell us in your own words.\n\n",
                    "Tukupigie saa ngapi? Jibu na nambari, au tuambie kwa maneno yako.\n\n"}),
            // The time goes on its own line rather than inside the sentence:
            // the customer may have answered in their own words, and "we'll
            // call you in the after 7pm on weekends only" is not English.
            Map.entry("leadDone", new String[]{
                    "✅ Got it. We'll call you on this number to arrange a site visit and quote you for installation.\n🕐 When: %s\n\nReply *menu* for anything else.",
                    "✅ Tumepokea. Tutakupigia kwa nambari hii kupanga ziara na kukupa bei ya usakinishaji.\n🕐 Wakati: %s\n\nJibu *menu* kwa lolote lingine."}),
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
                    "Reply with the *{pay} number* to pay from (2547XXXXXXXX), or reply *me* to use this number.",
                    "Jibu na *nambari ya {pay}* ya kulipa (2547XXXXXXXX), au jibu *me* kutumia hii."}),
            Map.entry("badPhone", new String[]{
                    "That doesn't look like a valid {pay} number. Reply as 2547XXXXXXXX, or *me*.",
                    "Nambari si sahihi. Jibu kama 2547XXXXXXXX, au *me*."}),
            Map.entry("buySent", new String[]{
                    "✅ {pay} request sent to %s for %s. Enter your PIN — your WiFi code arrives here once confirmed.",
                    "✅ Ombi la {pay} limetumwa kwa %s la %s. Weka PIN yako — nambari ya WiFi itakuja hapa baada ya kuthibitishwa."}),
            Map.entry("payFail", new String[]{
                    "Sorry, we couldn't start the {pay} payment right now. Please try again shortly.",
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
                    "✅ {pay} request sent to renew for %d month(s). Enter your PIN — your internet stays on once confirmed.",
                    "✅ Ombi la {pay} limetumwa kuongeza miezi %d. Weka PIN yako — intaneti itaendelea baada ya kuthibitishwa."}),
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
            Map.entry("passActions", new String[]{
                    "\n*1* Log out my other device\n*2* Change my code\n*0* Back",
                    "\n*1* Ondoa kifaa kingine\n*2* Badilisha nambari yangu\n*0* Rudi"}),
            Map.entry("signedOut", new String[]{
                    "✅ Signed out. Your code is free to use on another device — your remaining time is untouched.",
                    "✅ Imeondolewa. Nambari yako inaweza kutumika kwenye kifaa kingine — muda wako haujaguswa."}),
            Map.entry("signedOutOffline", new String[]{
                    "We couldn't reach the router just now, so nothing was signed out. Please try again shortly.",
                    "Hatukuweza kufikia rauta sasa. Jaribu tena baadaye."}),
            Map.entry("reissued", new String[]{
                    "🔐 Done. Your new code is *%s* — use it as both username and password.\n"
                            + "The old code has stopped working everywhere, including on any device still using it.\n"
                            + "You keep the %s you had left.",
                    "🔐 Imekamilika. Nambari yako mpya ni *%s* — itumie kama jina na nenosiri.\n"
                            + "Nambari ya zamani haitumiki tena popote.\n"
                            + "Umebakiwa na %s."}),
            Map.entry("reissueFail", new String[]{
                    "Sorry, that didn't work: %s",
                    "Samahani, haikufanikiwa: %s"}),
            Map.entry("resendFound", new String[]{"🎟️ Your latest code is *%s* (%s).", "🎟️ Nambari yako ya hivi karibuni ni *%s* (%s)."}),
            Map.entry("planNo", new String[]{"Reply with a plan number from the list, or *menu* to go back.", "Jibu na nambari ya kifurushi, au *menu*."}),
            Map.entry("unknown", new String[]{"Sorry, I didn't get that.\n\n%s", "Samahani, sijaelewa.\n\n%s"}),
            Map.entry("langSet", new String[]{"Language set to English.", "Lugha imewekwa Kiswahili."})
    );

    private String t(Session s, String key, Object... args) {
        String[] v = M.get(key);
        String base = v == null ? key : v["SW".equals(s.lang) ? 1 : 0];
        // {pay} is filled before formatting, so the brand can never be mistaken
        // for a format specifier — and so a Ghanaian customer is not asked for
        // an "M-Pesa number" by a bot that has no idea where it is.
        base = base.replace("{pay}", paymentBrand());
        return args.length == 0 ? base : String.format(base, args);
    }

    /** What paying is called here, falling back to Kenya's answer. */
    private String paymentBrand() {
        try {
            return messages.paymentBrand();
        } catch (Exception e) {
            return "M-Pesa";
        }
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
            case PASS -> handlePassAction(s, text);
            case CUSTOM_MINUTES -> handleCustomMinutes(s, text);
            case LEAD_NAME -> handleLeadName(s, text);
            case LEAD_LOCATION -> handleLeadLocation(s, text);
            case LEAD_PACKAGE -> handleLeadPackage(s, text);
            case LEAD_WHEN -> handleLeadWhen(s, fromPhone, text);
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
                        .append(" — ").append(money.format(p.getPrice())).append("\n");
            }
            CustomPlanSettings custom = customPlanService.settings();
            if (custom.isEnabled()) {
                s.planIds.add(CUSTOM_PLAN_CHOICE);
                sb.append(s.planIds.size()).append(") Choose your own time — from ")
                        .append(money.format(customPlanService.priceFor(custom.getMinMinutes(), custom)))
                        .append('\n');
            }
            s.step = Step.PLAN;
            return sb.append(t(s, "back")).toString();
        }
        if ("4".equals(text.trim())) { s.step = Step.SUPPORT_MSG; return t(s, "supportPrompt"); }
        if ("7".equals(text.trim())) { s.step = Step.LEAD_NAME; return t(s, "leadName"); }
        return t(s, "unknown", menu(s));
    }

    /**
     * Pay-per-minute. Offered as the last row of the plan list rather than a
     * menu entry of its own, because from the customer's side it is another way
     * of answering "which package" — and it only appears when the operator has
     * switched it on and priced it.
     */
    private String handleCustomMinutes(Session s, String text) {
        CustomPlanSettings cfg = customPlanService.settings();
        Integer minutes = asInt(text);
        if (minutes == null || minutes < cfg.getMinMinutes() || minutes > cfg.getMaxMinutes()) {
            return t(s, "customBad", cfg.getMinMinutes(), cfg.getMaxMinutes());
        }
        s.customMinutes = minutes;
        s.planId = null;
        s.step = Step.PAY_PHONE;
        return t(s, "customPrice", minutes,
                money.format(customPlanService.priceFor(minutes, cfg)))
                + t(s, "payPrompt");
    }

    // --- A line at home or the office ---

    private String handleLeadName(Session s, String text) {
        String name = text.trim();
        if (name.length() < 2) {
            return t(s, "leadName");
        }
        s.leadName = name;
        s.step = Step.LEAD_LOCATION;
        return t(s, "leadWhere", name.split("\\s+")[0]);
    }

    private String handleLeadLocation(Session s, String text) {
        s.leadLocation = text.trim();
        s.step = Step.LEAD_PACKAGE;

        // Offer the real packages where the operator has defined them. Until
        // they have, asking is better than presenting an empty list or
        // inventing tiers from whatever existing subscribers happen to be on.
        List<Plan> pppoe = plans.findByActiveTrueOrderByPriceAsc().stream()
                .filter(p -> p.getEffectiveType() == Plan.Type.PPPOE)
                .filter(p -> p.getAvailability() == null || p.getAvailability() == Plan.Availability.LIVE)
                .toList();
        s.leadPlanIds.clear();
        if (pppoe.isEmpty()) {
            return t(s, "leadPackageOpen");
        }
        StringBuilder sb = new StringBuilder(t(s, "leadPackageList"));
        for (int i = 0; i < pppoe.size(); i++) {
            Plan p = pppoe.get(i);
            s.leadPlanIds.add(p.getId());
            sb.append(i + 1).append(") ").append(p.getName())
                    .append(p.getBandwidth() == null ? "" : " — " + p.getBandwidth())
                    .append(" — ").append(money.format(p.getPrice()))
                    .append("/month\n");
        }
        return sb.append(t(s, "leadPackageAdvise")).toString();
    }

    /** Either a number from the list, or their own words when there is no list. */
    private String handleLeadPackage(Session s, String text) {
        String answer = text.trim();
        if (!s.leadPlanIds.isEmpty()) {
            Integer idx = asInt(answer);
            if (idx != null && idx >= 1 && idx <= s.leadPlanIds.size()) {
                Plan chosen = plans.findById(s.leadPlanIds.get(idx - 1)).orElse(null);
                answer = chosen == null ? answer : chosen.getName()
                        + " (" + money.format(chosen.getPrice()) + "/month)";
            } else if (!answer.equalsIgnoreCase("advise")) {
                return t(s, "leadPackageAgain", s.leadPlanIds.size());
            }
        }
        s.leadPackage = answer.equalsIgnoreCase("advise")
                ? "Wants a recommendation" : answer;
        s.step = Step.LEAD_WHEN;
        StringBuilder sb = new StringBuilder(t(s, "leadWhen"));
        for (int i = 0; i < CALLBACK_SLOTS.length; i++) {
            sb.append('*').append(i + 1).append("* — ").append(CALLBACK_SLOTS[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * Turns the request into a Lead, which is where the sales pipeline already
     * lives — the office sees it beside walk-ins and phone enquiries rather
     * than in a separate inbox nobody checks.
     */
    private String handleLeadWhen(Session s, String fromPhone, String text) {
        Integer pick = asInt(text);
        String when = pick != null && pick >= 1 && pick <= CALLBACK_SLOTS.length
                ? CALLBACK_SLOTS[pick - 1]
                // Anything else is taken at their word: "after 6", "weekends
                // only" and "call my wife on 0722…" are all more useful to the
                // person making the call than a rejected reply would be.
                : text.trim();

        String name = s.leadName;
        String location = s.leadLocation;
        String wanted = s.leadPackage;
        reset(s);

        Lead lead = leads.save(Lead.builder()
                .fullName(name == null ? "WhatsApp enquiry" : name)
                .phoneNumber(loose(fromPhone))
                .location(location)
                .interestedIn(wanted == null || wanted.isBlank()
                        ? "Home/office internet (PPPoE)" : wanted)
                .notes("Asked over WhatsApp. Prefers a call: " + when)
                .source(Lead.Source.ONLINE)
                .status(Lead.Status.NEW)
                .createdBy("whatsapp")
                .build());
        log.info("WhatsApp connection request from {} became lead {}", fromPhone, lead.getId());
        return t(s, "leadDone", when);
    }

    private String handlePlan(Session s, String text) {
        Integer idx = asInt(text);
        if (idx == null || idx < 1 || idx > s.planIds.size()) return t(s, "planNo");
        Long chosen = s.planIds.get(idx - 1);
        if (chosen == CUSTOM_PLAN_CHOICE) {
            CustomPlanSettings cfg = customPlanService.settings();
            s.step = Step.CUSTOM_MINUTES;
            return t(s, "customAsk", cfg.getMinMinutes(), cfg.getMaxMinutes(),
                    money.format(cfg.getPricePerHour()));
        }
        s.planId = chosen;
        s.step = Step.PAY_PHONE;
        return t(s, "payPrompt");
    }

    private String handleBuyPhone(Session s, String fromPhone, String text) {
        String phone = "me".equalsIgnoreCase(text.trim()) ? normalize(fromPhone) : normalize(text);
        if (phone == null) return t(s, "badPhone");

        // Pay-per-minute takes a different STK call, since the amount comes
        // from the minutes asked for rather than from a plan's price.
        if (s.customMinutes != null) {
            int minutes = s.customMinutes;
            String price = money.format(
                    customPlanService.priceFor(minutes, customPlanService.settings()));
            reset(s);
            try {
                payments.initiateCustomStkPush(phone, minutes);
            } catch (Exception e) {
                log.warn("WhatsApp custom buy STK failed for {}: {}", phone, e.getMessage());
                return t(s, "payFail");
            }
            return t(s, "buySent", phone, price);
        }

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
        return t(s, "buySent", phone, money.format(p.getPrice()));
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
        // Nobody is assigned to a ticket a customer opened, so without this the
        // technicians never hear it exists.
        try {
            fieldOps.notifyNewTicket(ticket);
        } catch (Exception e) {
            log.warn("Could not tell the technicians about ticket {}: {}", ticket.getId(), e.getMessage());
        }
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
        if (v == null) {
            return t(s, "statusNone");
        }
        s.voucherId = v.getId();
        s.step = Step.PASS;
        return passCard(s, v);
    }

    /**
     * What the customer is actually asking when they say "how much is left" —
     * the code, the time, and the data if their package caps it. A finished
     * pass shows no actions, because neither of them does anything useful on
     * one.
     */
    private String passCard(Session s, Voucher v) {
        VoucherService.PassStatus st = voucherService.statusOf(v);
        StringBuilder sb = new StringBuilder("🎟️ *")
                .append(st.code()).append("*")
                .append(st.planName() == null ? "" : " · " + st.planName()).append('\n');

        if (st.minutesLeft() <= 0) {
            sb.append("This pass has finished. Reply *1* to buy another.");
            s.step = Step.MENU;
            s.voucherId = null;
            return sb.toString();
        }

        sb.append("⏳ Time left: *").append(humanMinutes(st.minutesLeft())).append("*\n");
        if (st.expiresAt() != null) {
            sb.append("📅 Valid until ").append(TIME.format(st.expiresAt()))
                    .append(" on ").append(DATE.format(st.expiresAt())).append('\n');
        }
        if (st.capMb() != null) {
            sb.append("📶 Data: ").append(st.usedMb()).append(" MB of ").append(st.capMb())
                    .append(" MB used — *").append(st.mbLeft()).append(" MB left*\n");
        } else if (st.usedMb() > 0) {
            sb.append("📶 Data used: ").append(st.usedMb()).append(" MB (no limit on this package)\n");
        }
        return sb.append(t(s, "passActions")).toString();
    }

    private String handlePassAction(Session s, String text) {
        Voucher v = s.voucherId == null ? null : vouchers.findById(s.voucherId).orElse(null);
        if (v == null) {
            reset(s);
            return t(s, "statusNone");
        }
        switch (text.trim()) {
            case "1" -> {
                boolean done;
                try {
                    done = voucherService.signOutDevices(v);
                } catch (Exception e) {
                    return t(s, "reissueFail", e.getMessage());
                }
                return (done ? t(s, "signedOut") : t(s, "signedOutOffline")) + "\n\n" + passCard(s, v);
            }
            case "2" -> {
                String had = humanMinutes(voucherService.statusOf(v).minutesLeft());
                try {
                    Voucher fresh = voucherService.reissueUnderNewCode(v);
                    reset(s);
                    return t(s, "reissued", fresh.getCode(), had);
                } catch (Exception e) {
                    return t(s, "reissueFail", e.getMessage());
                }
            }
            case "0" -> {
                reset(s);
                return menu(s);
            }
            default -> {
                return t(s, "unknown", passCard(s, v));
            }
        }
    }

    /** "3h 20m" rather than "200 minutes", which nobody reads as three hours. */
    private static String humanMinutes(long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    private String resend(Session s, String fromPhone) {
        Voucher v = vouchers.findByPhoneNumberOrderByCreatedAtDesc(loose(fromPhone)).stream().findFirst().orElse(null);
        return v == null ? t(s, "resendNone") : t(s, "resendFound", v.getCode(), voucherState(v));
    }

    /**
     * Read from the clock, not from the stored status. The sweep that marks a
     * pass EXPIRED runs every couple of minutes, so for that window a pass
     * whose time has gone is still stored as UNUSED — and the bot would tell
     * the customer their dead code was "ready to use" in the same breath as
     * option 2 telling them it had finished.
     */
    private String voucherState(Voucher v) {
        if (voucherService.statusOf(v).minutesLeft() <= 0) {
            return "expired";
        }
        return switch (v.getStatus()) {
            case UNUSED -> "ready to use";
            case ACTIVE -> "in use";
            case EXPIRED -> "finished";
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
                // getEffectiveType, not getType: a plan created before the
                // column existed has no type, and hotspot is what that means.
                // Reading it raw hid nine of this operator's eleven packages
                // from the bot while the portal and USSD sold them happily.
                .filter(p -> p.getEffectiveType() == Plan.Type.HOTSPOT)
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
        s.voucherId = null;
        s.customMinutes = null;
        s.leadName = null;
        s.leadLocation = null;
        s.leadPackage = null;
        s.leadPlanIds.clear();
        s.planIds.clear();
    }

    private static Integer asInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    /**
     * One canonical form for a number, whatever shape it was typed in.
     *
     * <p>Was a private copy of a Kenyan normaliser hardcoding "254" — one of
     * five identical copies, and the reason a Ghanaian ISP could configure
     * everything correctly and still take no money.
     */
    private String normalize(String raw) {
        return phones.normalise(raw);
    }
    private String loose(String raw) {
        String n = normalize(raw);
        return n != null ? n : (raw == null ? "" : raw.replaceAll("\\D", ""));
    }
}
