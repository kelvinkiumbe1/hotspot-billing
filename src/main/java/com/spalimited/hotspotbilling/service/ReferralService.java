package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Referral;
import com.spalimited.hotspotbilling.domain.ReferralClaim;
import com.spalimited.hotspotbilling.domain.ReferralSettings;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.ReferralClaimRepository;
import com.spalimited.hotspotbilling.repository.ReferralRepository;
import com.spalimited.hotspotbilling.repository.ReferralSettingsRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Referral programme: a customer shares their code; when a new customer they
 * referred makes their first purchase, both are rewarded with free-minute
 * vouchers. Rewards are vouchers (not loyalty points) so they don't depend on
 * the loyalty programme being on.
 *
 * <p>Flow: the new customer enters a friend's code before buying
 * ({@link #submitClaim}), which records a PENDING claim; their first successful
 * purchase calls {@link #settleIfPending}, which rewards both and settles it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReferralService {

    // No confusable characters (no O/0, I/1) so codes are easy to read aloud.
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private final ReferralSettingsRepository settingsRepo;
    private final ReferralRepository referrals;
    private final ReferralClaimRepository claims;
    private final VoucherRepository vouchers;
    private final SubscriberRepository subscribers;
    private final VoucherService voucherService;
    private final CustomPlanService customPlanService;
    private final SmsService smsService;
    private final PortalSettingsService portalSettings;

    // --- Settings ---

    @Transactional
    public ReferralSettings settings() {
        return settingsRepo.findById(ReferralSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(ReferralSettings.builder()
                        .id(ReferralSettings.SINGLETON_ID).build()));
    }

    @Transactional
    public ReferralSettings saveSettings(ReferralSettings in) {
        ReferralSettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setReferrerMinutes(Math.max(1, in.getReferrerMinutes()));
        s.setRefereeMinutes(Math.max(1, in.getRefereeMinutes()));
        return settingsRepo.save(s);
    }

    // --- A customer's own code ---

    /** Get-or-create this phone's shareable referral code. */
    @Transactional
    public Referral codeFor(String phoneNumber) {
        return referrals.findById(phoneNumber)
                .orElseGet(() -> referrals.save(Referral.builder()
                        .phoneNumber(phoneNumber).code(uniqueCode()).build()));
    }

    // --- The referee's claim ---

    /**
     * Records that a new customer was referred with {@code code}. Rejects with a
     * readable reason if referrals are off, the code is unknown, they'd be
     * referring themselves, they've already used a referral, or they're not a
     * new customer.
     */
    @Transactional
    public Map<String, Object> submitClaim(String refereePhone, String code) {
        ReferralSettings s = settings();
        if (!s.isEnabled()) {
            throw new IllegalStateException("The referral programme isn't available right now");
        }
        if (refereePhone == null || refereePhone.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Enter your phone number and a referral code");
        }
        String normalizedCode = code.trim().toUpperCase();
        Referral referrer = referrals.findByCode(normalizedCode)
                .orElseThrow(() -> new IllegalArgumentException("That referral code isn't valid"));
        if (referrer.getPhoneNumber().equals(refereePhone)) {
            throw new IllegalArgumentException("You can't refer yourself");
        }
        if (claims.findByRefereePhone(refereePhone).isPresent()) {
            throw new IllegalStateException("You've already used a referral code");
        }
        if (!vouchers.findByPhoneNumberOrderByCreatedAtDesc(refereePhone).isEmpty()
                || !subscribers.findByPhoneNumber(refereePhone).isEmpty()) {
            throw new IllegalStateException("Referral codes are for new customers only");
        }
        claims.save(ReferralClaim.builder()
                .refereePhone(refereePhone)
                .code(normalizedCode)
                .referrerPhone(referrer.getPhoneNumber())
                .status(ReferralClaim.Status.PENDING)
                .build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "pending");
        out.put("message", "Referral code applied! You'll both get free minutes when you make your first purchase.");
        out.put("refereeMinutes", s.getRefereeMinutes());
        return out;
    }

    // --- Settlement on first purchase ---

    /**
     * Called on a customer's successful purchase. If they have a PENDING
     * referral claim, reward both the referrer and this referee with free-minute
     * vouchers and mark it settled. A no-op otherwise, and never throws into the
     * purchase path (the caller still wraps it defensively).
     */
    @Transactional
    public void settleIfPending(String refereePhone) {
        if (refereePhone == null || refereePhone.isBlank()) {
            return;
        }
        ReferralClaim claim = claims.findByRefereePhone(refereePhone).orElse(null);
        if (claim == null || claim.getStatus() != ReferralClaim.Status.PENDING) {
            return;
        }
        ReferralSettings s = settings();
        String business = portalSettings.settings().getBusinessName();

        reward(refereePhone, s.getRefereeMinutes(),
                "Welcome to " + business + "! Your referral bonus: code %s for " + s.getRefereeMinutes()
                        + " minutes of free WiFi. Enjoy!");
        reward(claim.getReferrerPhone(), s.getReferrerMinutes(),
                "Someone just joined " + business + " with your referral code! Enjoy " + s.getReferrerMinutes()
                        + " minutes of free WiFi: code %s. Thanks for sharing!");

        referrals.findById(claim.getReferrerPhone()).ifPresent(r -> {
            r.setSuccessfulReferrals(r.getSuccessfulReferrals() + 1);
            referrals.save(r);
        });
        claim.setStatus(ReferralClaim.Status.SETTLED);
        claim.setSettledAt(Instant.now());
        claims.save(claim);
        log.info("Referral settled: referee {} + referrer {} rewarded", refereePhone, claim.getReferrerPhone());
    }

    // --- Admin view ---

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settled", claims.countByStatus(ReferralClaim.Status.SETTLED));
        out.put("pending", claims.countByStatus(ReferralClaim.Status.PENDING));
        out.put("topReferrers", referrals
                .findTop50BySuccessfulReferralsGreaterThanOrderBySuccessfulReferralsDesc(0));
        out.put("recentClaims", claims.findTop50ByOrderByCreatedAtDesc());
        return out;
    }

    // --- helpers ---

    /** Issues a free-minute voucher and texts/WhatsApps the code. */
    private void reward(String phone, int minutes, String messageTemplate) {
        if (phone == null || phone.isBlank() || minutes <= 0) {
            return;
        }
        Voucher voucher = voucherService.issueCustom(
                customPlanService.systemPlan(customPlanService.settings()),
                phone, minutes, null, null, "referral");
        smsService.trySend(phone, String.format(messageTemplate, voucher.getCode()));
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < 25; attempt++) {
            StringBuilder sb = new StringBuilder("REF");
            for (int i = 0; i < 5; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (referrals.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique referral code");
    }
}
