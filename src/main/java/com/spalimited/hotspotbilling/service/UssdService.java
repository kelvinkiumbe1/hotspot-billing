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
                return "CON " + business() + "\n1. Buy WiFi\n2. My code\n3. My account\n4. Pay by M-Pesa";
            }
            return switch (steps[0]) {
                case "1" -> buy(phone, steps);
                case "2" -> lastCode(phone);
                case "3" -> account(phone, steps);
                case "4" -> payInstructions();
                default -> "END Sorry, that isn't one of the options. Please dial again.";
            };
        } catch (Exception e) {
            log.warn("USSD failed for {}: {}", phoneNumber, e.getMessage());
            return "END Sorry, something went wrong. Please try again shortly.";
        }
    }

    // --- 1. Buy WiFi ---

    private String buy(String phone, String[] steps) {
        List<Plan> live = livePlans();
        if (live.isEmpty()) {
            return "END No packages are on sale right now. Please try again later.";
        }
        if (steps.length == 1) {
            StringBuilder sb = new StringBuilder("CON Choose a package:");
            for (int i = 0; i < live.size(); i++) {
                Plan p = live.get(i);
                sb.append("\n").append(i + 1).append(". ").append(p.getName())
                        .append(" KES ").append(money(p));
            }
            return sb.toString();
        }

        Integer choice = asInt(steps[1]);
        if (choice == null || choice < 1 || choice > live.size()) {
            return "END That wasn't one of the packages. Please dial again.";
        }
        Plan plan = live.get(choice - 1);

        // A confirmation screen before any money moves: one stray keypress on a
        // basic handset should not charge somebody's M-Pesa.
        if (steps.length == 2) {
            return "CON " + plan.getName() + " for KES " + money(plan)
                    + "\n1. Send M-Pesa request to " + phone + "\n0. Cancel";
        }
        if (!"1".equals(steps[2])) {
            return "END Cancelled. Nothing has been charged.";
        }
        if (phone == null) {
            return "END We couldn't read your number. Please buy from the WiFi page instead.";
        }
        try {
            payments.initiateStkPush(phone, plan.getId());
        } catch (Exception e) {
            log.warn("USSD STK failed for {}: {}", phone, e.getMessage());
            return "END We couldn't start the M-Pesa payment. Please try again shortly.";
        }
        return "END Check your phone for the M-Pesa prompt. Your WiFi code arrives by SMS once paid.";
    }

    // --- 2. My code ---

    private String lastCode(String phone) {
        Voucher v = phone == null ? null
                : vouchers.findByPhoneNumberOrderByCreatedAtDesc(phone).stream().findFirst().orElse(null);
        if (v == null) {
            return "END No WiFi code found for this number. Dial again and choose 1 to buy one.";
        }
        String state = switch (v.getStatus()) {
            case UNUSED -> "ready to use";
            case ACTIVE -> "in use";
            case EXPIRED -> "finished";
        };
        return "END Your code is " + v.getCode() + " (" + v.getPlan().getName() + ", " + state
                + "). Use it as both username and password.";
    }

    // --- 3. My account ---

    private String account(String phone, String[] steps) {
        Subscriber sub = phone == null ? null
                : subscribers.findByPhoneNumber(phone).stream().findFirst().orElse(null);
        if (sub == null) {
            return "END No home or office line is registered on this number. Choose 1 to buy a WiFi package.";
        }
        boolean active = sub.getStatus() == Subscriber.Status.ACTIVE
                && sub.getPaidUntil() != null && sub.getPaidUntil().isAfter(Instant.now());
        String until = sub.getPaidUntil() != null ? DATE.format(sub.getPaidUntil()) : "unknown";

        if (steps.length == 1) {
            return "CON " + (active ? "Active" : "Not active") + ", paid to " + until
                    + "\n1. Renew 1 month (KES " + sub.getMonthlyFee().stripTrailingZeros().toPlainString() + ")"
                    + "\n0. Exit";
        }
        if (!"1".equals(steps[1])) {
            return "END Thank you.";
        }
        try {
            subscriptions.initiateStk(sub.getId(), 1);
        } catch (Exception e) {
            log.warn("USSD renew failed for subscriber {}: {}", sub.getId(), e.getMessage());
            return "END We couldn't start the M-Pesa payment. Please try again shortly.";
        }
        return "END Check your phone for the M-Pesa prompt. Your internet stays on once it is paid.";
    }

    // --- 4. Pay by M-Pesa ---

    private String payInstructions() {
        Map<String, Object> manual = gateways.manualInstructions();
        Object paybill = manual.get("paybillNumber");
        Object till = manual.get("tillNumber");
        if (paybill != null) {
            return "END Go to M-Pesa > Pay Bill. Business no: " + paybill
                    + ". Account: your phone number. Your code arrives by SMS.";
        }
        if (till != null) {
            return "END Go to M-Pesa > Buy Goods. Till no: " + till + ". Your code arrives by SMS.";
        }
        return "END Please buy from the WiFi page, or call support for payment details.";
    }

    // --- helpers ---

    private List<Plan> livePlans() {
        return plans.findByActiveTrueOrderByPriceAsc().stream()
                // "Custom Time" exists only to hang pay-per-minute payments off;
                // it is a KES 1 one-minute row, never something to sell.
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

    private static String money(Plan p) {
        return p.getPrice().stripTrailingZeros().toPlainString();
    }

    private static Integer asInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 10 && d.startsWith("0")) {
            d = "254" + d.substring(1);
        }
        if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        return d.matches("254\\d{9}") ? d : null;
    }
}
