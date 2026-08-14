package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.CreditAdvanceRepository;
import com.spalimited.hotspotbilling.repository.CreditSettingsRepository;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Lipa Baadaye" — WiFi now, paid for on the next purchase.
 *
 * <p>The moment a prepaid customer loses is the moment their money hasn't
 * landed yet: the pass runs out mid-evening, the salary comes on Friday, and
 * they simply go offline. Every competitor's answer is to wait. Fuliza taught
 * the whole country to expect the other answer, and this is it — a customer who
 * has already paid several times can take a small pass on trust.
 *
 * <p>Underwriting is deliberately dull: a customer qualifies on their own
 * history with this operator (how many passes they have paid for, over how
 * long, and whether they have ever failed to settle), never on anything bought
 * in or guessed. One advance at a time, capped at one small pass, so the worst
 * case per customer is a single pass given away.
 *
 * <p>Recovery needs no chasing. The debt is added to the M-Pesa amount of the
 * customer's next purchase, so settling happens as a side effect of coming
 * back. Somebody who never returns costs the operator one pass and takes no
 * more credit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditService {

    private final CreditSettingsRepository settingsRepo;
    private final CreditAdvanceRepository advances;
    private final PaymentRepository payments;
    private final PlanRepository plans;
    private final VoucherService voucherService;
    private final NotificationService notifications;
    private final SmsService smsService;
    private final PortalSettingsService portalSettings;
    private final AuditService audit;

    /** Whether this customer may borrow, and what they owe already. */
    public record Eligibility(boolean enabled, boolean eligible, String reason,
                              BigDecimal limit, BigDecimal outstanding) {
    }

    // --- Settings ---

    @Transactional
    public CreditSettings settings() {
        return settingsRepo.findById(CreditSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(CreditSettings.builder()
                        .id(CreditSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public CreditSettings saveSettings(CreditSettings in) {
        CreditSettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setMinPurchases(clamp(in.getMinPurchases(), 1, 50));
        s.setMinDaysKnown(clamp(in.getMinDaysKnown(), 0, 365));
        s.setFeePercent(clamp(in.getFeePercent(), 0, 50));
        s.setRepayWithinHours(clamp(in.getRepayWithinHours(), 1, 720));
        s.setMaxDefaults(clamp(in.getMaxDefaults(), 1, 10));
        if (in.getMaxAdvance() != null && in.getMaxAdvance().signum() > 0) {
            s.setMaxAdvance(in.getMaxAdvance());
        }
        return settingsRepo.save(s);
    }

    // --- Underwriting ---

    /**
     * Scores one customer against their own history. Every "no" carries the
     * reason, because the portal shows it — "two more purchases and you can" is
     * a reason to come back, where a blank refusal is a reason to leave.
     */
    @Transactional(readOnly = true)
    public Eligibility eligibility(String phoneNumber) {
        CreditSettings s = settings();
        String phone = normalize(phoneNumber);
        BigDecimal outstanding = outstandingFor(phone);

        if (!s.isEnabled()) {
            return new Eligibility(false, false, "Not available", BigDecimal.ZERO, outstanding);
        }
        if (phone == null) {
            return new Eligibility(true, false, "We need your M-Pesa number first", s.getMaxAdvance(), BigDecimal.ZERO);
        }
        if (outstanding.signum() > 0) {
            return new Eligibility(true, false,
                    "You already have KES " + plain(outstanding) + " to settle", s.getMaxAdvance(), outstanding);
        }
        if (advances.countByPhoneNumberAndStatus(phone, CreditAdvance.Status.DEFAULTED) >= s.getMaxDefaults()) {
            return new Eligibility(true, false, "Not available on this number", s.getMaxAdvance(), outstanding);
        }

        List<Payment> paid = payments.findByPhoneNumberOrderByCreatedAtDesc(phone).stream()
                .filter(p -> p.getStatus() == Payment.Status.SUCCESS)
                .toList();
        if (paid.size() < s.getMinPurchases()) {
            int more = s.getMinPurchases() - paid.size();
            return new Eligibility(true, false,
                    more + " more purchase" + (more == 1 ? "" : "s") + " and you can pay later",
                    s.getMaxAdvance(), outstanding);
        }
        Instant first = paid.stream().map(Payment::getCreatedAt).min(Comparator.naturalOrder()).orElse(Instant.now());
        long daysKnown = Duration.between(first, Instant.now()).toDays();
        if (daysKnown < s.getMinDaysKnown()) {
            long more = s.getMinDaysKnown() - daysKnown;
            return new Eligibility(true, false,
                    "Available after " + more + " more day" + (more == 1 ? "" : "s") + " with us",
                    s.getMaxAdvance(), outstanding);
        }
        return new Eligibility(true, true, "You can take a pass now and pay on your next purchase",
                s.getMaxAdvance(), outstanding);
    }

    /** What this number must settle before anything else. */
    @Transactional(readOnly = true)
    public BigDecimal outstandingFor(String phoneNumber) {
        String phone = normalize(phoneNumber);
        if (phone == null) {
            return BigDecimal.ZERO;
        }
        return advances.findByPhoneNumberAndStatus(phone, CreditAdvance.Status.OUTSTANDING).stream()
                .map(CreditAdvance::getTotalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // --- Taking a pass on credit ---

    @Transactional
    public Map<String, Object> take(String phoneNumber, Long planId) {
        CreditSettings s = settings();
        String phone = normalize(phoneNumber);
        Eligibility check = eligibility(phone);
        if (!check.eligible()) {
            throw new IllegalStateException(check.reason());
        }
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + planId));
        if (!plan.isUsable() || plan.getEffectiveType() != Plan.Type.HOTSPOT) {
            throw new IllegalStateException("That package isn't available");
        }
        if (plan.getPrice().compareTo(s.getMaxAdvance()) > 0) {
            throw new IllegalStateException("Pay later covers packages up to KES " + plain(s.getMaxAdvance()));
        }

        BigDecimal fee = plan.getPrice()
                .multiply(BigDecimal.valueOf(s.getFeePercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        BigDecimal totalDue = plan.getPrice().add(fee);
        Instant now = Instant.now();

        // Stamped as credit so the revenue audit reads it as a pass with a
        // reason behind it, not service that appeared from nowhere.
        Voucher voucher = voucherService.issue(plan, phone, null, null, "credit");

        CreditAdvance advance = advances.save(CreditAdvance.builder()
                .phoneNumber(phone)
                .planId(plan.getId())
                .voucherCode(voucher.getCode())
                .amount(plan.getPrice())
                .fee(fee)
                .totalDue(totalDue)
                .status(CreditAdvance.Status.OUTSTANDING)
                .issuedAt(now)
                .dueAt(now.plus(Duration.ofHours(s.getRepayWithinHours())))
                .build());

        notifications.send(NotificationTemplate.Key.VOUCHER_ISSUED, phone, Map.of(
                "business", business(),
                "code", voucher.getCode()));
        smsService.trySend(phone, "You have taken " + plan.getName() + " on credit. KES " + plain(totalDue)
                + " will be added to your next purchase. Asante!");

        audit.system("credit.advance", "Advanced " + plan.getName() + " (KES " + plain(totalDue)
                + ") to " + phone + " as " + voucher.getCode());
        log.info("Credit advance {} to {} for {}", advance.getId(), phone, plan.getName());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", voucher.getCode());
        out.put("plan", plan.getName());
        out.put("dueAmount", totalDue);
        out.put("dueAt", advance.getDueAt());
        return out;
    }

    // --- Recovery ---

    /**
     * Settles everything this number owes, called once a payment from them has
     * succeeded. The debt was added to that payment's amount, so by the time
     * the money is in, it has already been collected.
     */
    @Transactional
    public BigDecimal settle(String phoneNumber, String note) {
        String phone = normalize(phoneNumber);
        if (phone == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal cleared = BigDecimal.ZERO;
        for (CreditAdvance a : advances.findByPhoneNumberAndStatus(phone, CreditAdvance.Status.OUTSTANDING)) {
            a.setStatus(CreditAdvance.Status.REPAID);
            a.setRepaidAt(Instant.now());
            a.setRepaidNote(note);
            advances.save(a);
            cleared = cleared.add(a.getTotalDue());
        }
        if (cleared.signum() > 0) {
            audit.system("credit.settle", "Settled KES " + plain(cleared) + " of credit for " + phone
                    + (note == null ? "" : " (" + note + ")"));
            log.info("Settled KES {} of credit for {}", plain(cleared), phone);
        }
        return cleared;
    }

    /**
     * Chases what is past due: one reminder, then the advance is written off
     * and that number takes no more credit. There is no debt collection here
     * by design — the exposure is one small pass, and hounding somebody over
     * it costs more in goodwill than the pass is worth.
     */
    @Scheduled(cron = "${credit.chase-cron:0 45 9 * * *}")
    @Transactional
    public void chaseOverdue() {
        CreditSettings s = settings();
        if (!s.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (CreditAdvance a : advances.findByStatusAndDueAtBefore(CreditAdvance.Status.OUTSTANDING, now)) {
            if (a.getRemindedAt() == null) {
                smsService.trySend(a.getPhoneNumber(), "Reminder: KES " + plain(a.getTotalDue())
                        + " for your " + business() + " pass will be added to your next purchase.");
                a.setRemindedAt(now);
                advances.save(a);
                continue;
            }
            // Reminded and still unpaid a full cycle later — stop counting on it.
            if (a.getRemindedAt().isBefore(now.minus(Duration.ofHours(s.getRepayWithinHours())))) {
                a.setStatus(CreditAdvance.Status.DEFAULTED);
                a.setRepaidNote("Not settled within " + s.getRepayWithinHours() + " hours of the reminder");
                advances.save(a);
                audit.system("credit.default", "Wrote off KES " + plain(a.getTotalDue())
                        + " advanced to " + a.getPhoneNumber());
            }
        }
    }

    // --- Admin view ---

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        List<CreditAdvance> open = advances.findByStatusOrderByDueAtAsc(CreditAdvance.Status.OUTSTANDING);
        List<CreditAdvance> bad = advances.findByStatusOrderByDueAtAsc(CreditAdvance.Status.DEFAULTED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings());
        out.put("outstanding", open);
        out.put("outstandingTotal", open.stream().map(CreditAdvance::getTotalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("defaultedCount", bad.size());
        out.put("defaultedTotal", bad.stream().map(CreditAdvance::getTotalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return out;
    }

    /** Forgives one advance by hand — a goodwill call the operator can make. */
    @Transactional
    public CreditAdvance writeOff(Long id, String actor) {
        CreditAdvance a = advances.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown advance: " + id));
        a.setStatus(CreditAdvance.Status.DEFAULTED);
        a.setRepaidNote("Written off by " + actor);
        audit.record(actor, "credit.writeoff", "Wrote off KES " + plain(a.getTotalDue())
                + " advanced to " + a.getPhoneNumber());
        return advances.save(a);
    }

    // --- helpers ---

    private String business() {
        String biz = portalSettings.settings().getBusinessName();
        return biz == null || biz.isBlank() ? "your WiFi" : biz;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static String normalize(String raw) {
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
