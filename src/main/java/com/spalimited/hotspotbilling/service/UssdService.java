package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * USSD self-service, for the customers a captive portal cannot reach: a feature
 * phone, a flat battery on the laptop, a device already off the network because
 * the pass ran out. They dial a short code and can buy, fetch their last code,
 * check their line or read the paybill details — no data, no app, no browser.
 *
 * <p>Shaped for Africa's Talking, the usual Kenyan aggregator: every request
 * carries the whole session's keypresses in {@code text}, joined with {@code *},
 * so the menu is a pure function of that string and nothing needs to be held
 * between requests. A reply beginning {@code CON} keeps the session open,
 * {@code END} closes it.
 *
 * <p>Screens are kept short on purpose — a USSD screen is about 160 characters
 * on a basic handset, and anything longer is silently cut off mid-word.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UssdService {

    /** How many plans fit on one USSD screen without truncation. */
    private static final int MAX_PLANS = 5;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());

    private final PlanRepository plans;
    private final PaymentService payments;
    private final SubscriptionService subscriptions;
    private final SubscriberRepository subscribers;
    private final VoucherRepository vouchers;
    private final PortalSettingsService portalSettings;
    private final PaymentGatewayService gateways;
    /** Named cash rather than money, since money(Plan) already meant something here. */
    private final MoneyService cash;
    private final com.spalimited.hotspotbilling.service.i18n.Messages messages;
    private final com.spalimited.hotspotbilling.service.i18n.PhoneNumbers phones;

    /**
     * Handles one USSD request. {@code text} is everything the caller has
     * pressed this session, e.g. "1*3" for "Buy WiFi" then the third plan.
     */
    public String handle(String phoneNumber, String text) {
        String phone = normalize(phoneNumber);
        String[] steps = (text == null ? "" : text.trim()).isEmpty()
                ? new String[0]
                : text.trim().split("\\*");

        try {
            if (steps.length == 0) {
                return "CON " + business() + "\n" + say("ussd.menu");
            }
            return switch (steps[0]) {
                case "1" -> buy(phone, steps);
                case "2" -> lastCode(phone);
                case "3" -> account(phone, steps);
                case "4" -> payInstructions();
                default -> "END " + say("ussd.badOption");
            };
        } catch (Exception e) {
            log.warn("USSD failed for {}: {}", phoneNumber, e.getMessage());
            return "END " + say("ussd.error");
        }
    }

    // --- 1. Buy WiFi ---

    private String buy(String phone, String[] steps) {
        List<Plan> live = livePlans();
        if (live.isEmpty()) {
            return "END " + say("ussd.noPlans");
        }
        if (steps.length == 1) {
            StringBuilder sb = new StringBuilder("CON " + say("ussd.choosePlan"));
            for (int i = 0; i < live.size(); i++) {
                Plan p = live.get(i);
                sb.append("\n").append(i + 1).append(". ").append(p.getName())
                        .append(" ").append(cash.format(price(p)));
            }
            return sb.toString();
        }

        Integer choice = asInt(steps[1]);
        if (choice == null || choice < 1 || choice > live.size()) {
            return "END " + say("ussd.badPlan");
        }
        Plan plan = live.get(choice - 1);

        // A confirmation screen before any money moves: one stray keypress on a
        // basic handset should not charge somebody's M-Pesa.
        if (steps.length == 2) {
            return "CON " + say("ussd.confirm", Map.of(
                    "plan", plan.getName(),
                    "price", cash.format(price(plan)),
                    "phone", phone == null ? "" : phone));
        }
        if (!"1".equals(steps[2])) {
            return "END " + say("ussd.cancelled");
        }
        if (phone == null) {
            return "END " + say("ussd.noNumber");
        }
        try {
            payments.initiateStkPush(phone, plan.getId());
        } catch (Exception e) {
            log.warn("USSD STK failed for {}: {}", phone, e.getMessage());
            return "END " + say("ussd.payFailed");
        }
        return "END " + say("ussd.checkPhone");
    }

    // --- 2. My code ---

    private String lastCode(String phone) {
        Voucher v = phone == null ? null
                : vouchers.findByPhoneNumberOrderByCreatedAtDesc(phone).stream().findFirst().orElse(null);
        if (v == null) {
            return "END " + say("ussd.noCode");
        }
        String state = say(switch (v.getStatus()) {
            case UNUSED -> "state.ready";
            case ACTIVE -> "state.inUse";
            case EXPIRED -> "state.finished";
        });
        return "END " + say("ussd.yourCode", Map.of(
                "code", v.getCode(), "plan", v.getPlan().getName(), "state", state));
    }

    // --- 3. My account ---

    private String account(String phone, String[] steps) {
        Subscriber sub = phone == null ? null
                : subscribers.findByPhoneNumber(phone).stream().findFirst().orElse(null);
        if (sub == null) {
            return "END " + say("ussd.noAccount");
        }
        boolean active = sub.getStatus() == Subscriber.Status.ACTIVE
                && sub.getPaidUntil() != null && sub.getPaidUntil().isAfter(Instant.now());
        String until = sub.getPaidUntil() != null ? DATE.format(sub.getPaidUntil()) : say("ussd.unknownDate");

        if (steps.length == 1) {
            return "CON " + say("ussd.accountScreen", Map.of(
                    "status", say(active ? "status.active" : "status.notActive"),
                    "date", until,
                    "price", cash.format(sub.getMonthlyFee())));
        }
        if (!"1".equals(steps[1])) {
            return "END " + say("ussd.thanks");
        }
        try {
            subscriptions.initiateStk(sub.getId(), 1);
        } catch (Exception e) {
            log.warn("USSD renew failed for subscriber {}: {}", sub.getId(), e.getMessage());
            return "END " + say("ussd.payFailed");
        }
        return "END " + say("ussd.checkPhoneRenew");
    }

    // --- 4. Pay by M-Pesa ---

    private String payInstructions() {
        Map<String, Object> manual = gateways.manualInstructions();
        Object paybill = manual.get("paybillNumber");
        Object till = manual.get("tillNumber");
        if (paybill != null) {
            return "END " + say("ussd.paybill", Map.of("paybill", String.valueOf(paybill)));
        }
        if (till != null) {
            return "END " + say("ussd.till", Map.of("till", String.valueOf(till)));
        }
        return "END " + say("ussd.noPayDetails");
    }

    // --- helpers ---

    /**
     * One line of USSD, in the operator's language.
     *
     * <p>Not the caller's: USSD carries no language hint at all — no header,
     * no browser, nothing but a phone number. So this is the one customer
     * surface where the operator's setting genuinely is the best available
     * answer rather than a fallback.
     */
    private String say(String key) {
        return messages.get(key);
    }

    private String say(String key, Map<String, String> values) {
        return messages.get(key, values);
    }


    private List<Plan> livePlans() {
        return plans.findByActiveTrueOrderByPriceAsc().stream()
                // "Custom Time" exists only to hang pay-per-minute payments off;
                // it is a one-minute holder row, never something to sell.
                .filter(p -> !CustomPlanService.SYSTEM_PLAN_NAME.equals(p.getName()))
                .filter(p -> p.getEffectiveType() == Plan.Type.HOTSPOT)
                .filter(Plan::isOnSale)
                .limit(MAX_PLANS)
                .toList();
    }

    private String business() {
        String biz = portalSettings.settings().getBusinessName();
        return biz == null || biz.isBlank() ? "WiFi" : biz;
    }

    private static java.math.BigDecimal price(Plan p) {
        return p.getPrice();
    }

    private static Integer asInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
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
}
