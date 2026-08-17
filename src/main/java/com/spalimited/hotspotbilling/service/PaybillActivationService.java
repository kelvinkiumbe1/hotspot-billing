package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.PayCodeRepository;
import com.spalimited.hotspotbilling.repository.PaybillSettingsRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Zero-touch activation: a customer sends money straight to the paybill and
 * gets online, with no STK prompt, no app and no smartphone required.
 *
 * <p>STK is the happy path only when everything lines up — Daraja reachable,
 * the customer's SIM able to take the prompt, the prompt not timing out while
 * they hunt for their PIN. In practice a good share of buyers fall out of that
 * flow and pay the paybill by hand instead, and until now that money landed as
 * an unmatched payment with the customer still offline, waiting for somebody to
 * notice.
 *
 * <p>Two things make it automatic. The captive portal hands each device a short
 * <em>pay code</em> to type as the account number, which ties the payment back
 * to that exact device; and the amount picks the pass — the best plan the money
 * affords. The code always goes out by WhatsApp/SMS, and where the operator has
 * enabled mac login on the router, the device is simply let on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaybillActivationService {

    /** No look-alike characters: a code read off a screen gets typed into a phone. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final PayCodeRepository payCodes;
    private final PaybillSettingsRepository settingsRepo;
    private final PlanRepository plans;
    private final RouterRepository routers;
    private final VoucherService voucherService;
    private final com.spalimited.hotspotbilling.repository.VoucherRepository vouchers;
    private final MikrotikService mikrotik;
    private final NotificationService notifications;
    private final SmsService smsService;
    private final PortalSettingsService portalSettings;
    private final MoneyService money;
    private final PaymentGatewayService gateways;
    private final CreditService credit;
    private final AuditService audit;

    private final SecureRandom random = new SecureRandom();

    /** What happened to one paybill payment. */
    public record Outcome(boolean activated, String voucherCode, String planName, String note) {
    }

    // --- Settings ---

    @Transactional
    public PaybillSettings settings() {
        return settingsRepo.findById(PaybillSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(PaybillSettings.builder()
                        .id(PaybillSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public PaybillSettings saveSettings(PaybillSettings in) {
        PaybillSettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setAutoLoginByMac(in.isAutoLoginByMac());
        s.setPayCodeMinutes(Math.max(5, Math.min(1440, in.getPayCodeMinutes())));
        s.setNotifyOnShortfall(in.isNotifyOnShortfall());
        if (in.getMaxAmount() != null && in.getMaxAmount().signum() >= 0) {
            s.setMaxAmount(in.getMaxAmount());
        }
        return settingsRepo.save(s);
    }

    // --- What the captive portal shows ---

    /**
     * The paybill instructions for one device: the number to pay and the
     * account number to type. Reuses the device's live code so a page refresh
     * doesn't hand out a different one mid-payment.
     */
    @Transactional
    public Map<String, Object> instructionsFor(String mac, Long routerId) {
        PaybillSettings s = settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.isEnabled());
        out.putAll(gateways.manualInstructions());
        if (!s.isEnabled()) {
            return out;
        }

        Instant now = Instant.now();
        String clean = normaliseMac(mac);
        PayCode code = clean == null ? null : payCodes
                .findFirstByMacAddressAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(clean, now)
                .orElse(null);
        if (code == null) {
            code = payCodes.save(PayCode.builder()
                    .code(uniqueCode())
                    .macAddress(clean)
                    .routerId(routerId)
                    .createdAt(now)
                    .expiresAt(now.plus(Duration.ofMinutes(s.getPayCodeMinutes())))
                    .build());
        }
        out.put("payCode", code.getCode());
        out.put("expiresAt", code.getExpiresAt());
        out.put("autoLogin", s.isAutoLoginByMac() && clean != null);
        return out;
    }

    /**
     * Whether the money against this pay code has landed yet, so the portal can
     * show the pass on screen the moment it does rather than making the
     * customer go and read their SMS.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> statusOf(String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        PayCode pc = code == null ? null
                : payCodes.findById(code.trim().toUpperCase()).orElse(null);
        out.put("found", pc != null);
        out.put("activated", pc != null && pc.getVoucherCode() != null);
        if (pc != null && pc.getVoucherCode() != null) {
            out.put("voucherCode", pc.getVoucherCode());
        }
        return out;
    }

    // --- What happens when the money lands ---

    /**
     * Turns a paybill payment that matched no subscriber into a hotspot pass.
     * Returns an outcome the caller records on the payment; an outcome that
     * isn't activated leaves the payment unmatched for the admin, exactly as
     * before.
     */
    @Transactional
    public Outcome activate(BigDecimal amount, String phoneNumber, String billRefNumber) {
        PaybillSettings s = settings();
        if (!s.isEnabled()) {
            return new Outcome(false, null, null, null);
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return new Outcome(false, null, null, "No paying number on the payment — cannot deliver a code");
        }
        if (s.getMaxAmount() != null && s.getMaxAmount().signum() > 0
                && amount.compareTo(s.getMaxAmount()) > 0) {
            return new Outcome(false, null, null, money.format(amount) + " is above the auto-issue limit of "
                    + s.getMaxAmount().stripTrailingZeros().toPlainString() + " — left for you to place");
        }

        PayCode payCode = resolvePayCode(billRefNumber);
        Long routerId = payCode != null ? payCode.getRouterId() : null;

        // A pay-later debt comes off the top, exactly as it would on an STK
        // purchase. Money that doesn't even cover the debt is left alone — part
        // -settling somebody's credit without telling them is worse than
        // putting the payment in front of a human.
        BigDecimal owed = credit.outstandingFor(phoneNumber);
        if (owed.signum() > 0 && amount.compareTo(owed) < 0) {
            return new Outcome(false, null, null, money.format(amount) + " received but " + money.format(owed)
                    + " is owed on a pay-later pass — not enough to settle it");
        }
        BigDecimal spendable = amount.subtract(owed);

        Plan plan = bestAffordablePlan(spendable, routerId).orElse(null);
        if (plan == null && owed.signum() > 0) {
            // It cleared the debt and no more. Say so rather than leaving them
            // wondering where their money went.
            credit.settle(phoneNumber, "PayBill payment of " + money.format(amount));
            smsService.trySend(phoneNumber, "Thank you — " + money.format(owed)
                    + " owed on your pay-later pass is now settled.");
            return new Outcome(true, null, null, "Settled " + money.format(owed) + " of pay-later credit; "
                    + "the remainder did not cover a package");
        }
        if (plan == null) {
            String note = money.format(amount) + " does not cover any hotspot plan on sale";
            if (s.isNotifyOnShortfall()) {
                smsService.trySend(phoneNumber, "We received " + money.format(amount) + " but it is less than our "
                        + "cheapest package. Please top up or call support and we will help.");
            }
            audit.system("paybill.shortfall", note + " (from " + phoneNumber + ")");
            return new Outcome(false, null, null, note);
        }

        Voucher voucher = voucherService.issue(plan, phoneNumber, null, null, "paybill");

        String mac = payCode != null ? payCode.getMacAddress() : null;
        boolean letOn = false;
        if (mac != null && s.isAutoLoginByMac()) {
            try {
                Router router = mikrotik.routerFor(routerId);
                mikrotik.provisionMacLogin(router, mac, plan, plan.getDurationMinutes());
                // Claim the MAC on the voucher so the pass and the device that
                // was let on are one thing, not two — the fair-use, sharing and
                // revenue-audit checks all read boundMac.
                voucher.setBoundMac(mac);
                vouchers.save(voucher);
                letOn = true;
            } catch (Exception e) {
                log.warn("Auto-login for {} failed, falling back to the code: {}", mac, e.getMessage());
            }
        }

        if (payCode != null) {
            payCode.setUsedAt(Instant.now());
            payCode.setVoucherCode(voucher.getCode());
            payCodes.save(payCode);
        }
        if (owed.signum() > 0) {
            credit.settle(phoneNumber, "PayBill payment of " + money.format(amount));
        }

        notifications.send(NotificationTemplate.Key.VOUCHER_ISSUED, phoneNumber, Map.of(
                "business", portalSettings.settings().getBusinessName(),
                "code", voucher.getCode()));

        String note = "Auto-issued " + plan.getName() + " (" + voucher.getCode() + ") for " + money.format(amount)
                + (payCode != null ? " against pay code " + payCode.getCode() : " matched by phone")
                + (letOn ? "; device " + mac + " let straight on" : "");
        audit.system("paybill.activate", note);
        log.info("Zero-touch activation: {} for {} ({})", plan.getName(), phoneNumber, voucher.getCode());
        return new Outcome(true, voucher.getCode(), plan.getName(), note);
    }

    /** A live, unspent pay code matching what the customer typed. */
    private PayCode resolvePayCode(String billRefNumber) {
        if (billRefNumber == null || billRefNumber.isBlank()) {
            return null;
        }
        // Customers type it with spaces, in lower case, sometimes with the
        // business name in front. Take the last run of code-shaped characters.
        String cleaned = billRefNumber.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        PayCode code = payCodes.findById(cleaned).orElse(null);
        if (code == null && cleaned.length() > 6) {
            code = payCodes.findById(cleaned.substring(cleaned.length() - 6)).orElse(null);
        }
        if (code == null || code.isSpent() || code.getExpiresAt().isBefore(Instant.now())) {
            return null;
        }
        return code;
    }

    /**
     * The best pass the money buys: the dearest plan on sale, usable now, sold
     * on that router, whose price the amount covers. Dearest rather than exact
     * so a customer who rounds up to a note they actually have gets the better
     * package instead of an unmatched payment.
     */
    private Optional<Plan> bestAffordablePlan(BigDecimal amount, Long routerId) {
        LocalTime now = LocalTime.now();
        return plans.findByActiveTrueOrderByPriceAsc().stream()
                // Never the pay-per-minute holder row — a customer who sent a
                // few shillings should be told it's short, not sold a minute.
                .filter(p -> !CustomPlanService.SYSTEM_PLAN_NAME.equals(p.getName()))
                .filter(p -> p.getEffectiveType() == Plan.Type.HOTSPOT)
                .filter(Plan::isOnSale)
                .filter(p -> p.isUsableAt(now))
                .filter(p -> p.allowsRouter(routerId))
                .filter(p -> p.getPrice() != null && p.getPrice().signum() > 0)
                .filter(p -> amount.compareTo(p.getPrice()) >= 0)
                .max(Comparator.comparing(Plan::getPrice));
    }

    // --- housekeeping ---

    /** Spent and stale codes are noise; clear them out once a day. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeExpired() {
        var stale = payCodes.findByExpiresAtBeforeAndUsedAtIsNull(Instant.now().minus(Duration.ofDays(2)));
        if (!stale.isEmpty()) {
            payCodes.deleteAll(stale);
            log.debug("Purged {} expired pay code(s)", stale.size());
        }
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < 30; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (!payCodes.existsById(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not allocate a pay code");
    }

    /** RouterOS gives MACs upper-case colon-separated; normalise what we're handed. */
    private static String normaliseMac(String mac) {
        if (mac == null || mac.isBlank()) {
            return null;
        }
        String cleaned = mac.trim().toUpperCase().replace('-', ':');
        return cleaned.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") ? cleaned : null;
    }
}
