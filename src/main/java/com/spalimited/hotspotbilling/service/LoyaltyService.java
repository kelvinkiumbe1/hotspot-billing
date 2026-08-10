package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.LoyaltyAccount;
import com.spalimited.hotspotbilling.domain.LoyaltySettings;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.LoyaltyAccountRepository;
import com.spalimited.hotspotbilling.repository.LoyaltySettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The loyalty programme. Points are earned automatically as customers pay
 * and redeemed for free minutes. Redemption always delivers the voucher by
 * SMS to the account's phone, so redeeming someone else's points can never
 * hand their reward to a stranger.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyService {

    private final LoyaltySettingsRepository settingsRepo;
    private final LoyaltyAccountRepository accounts;
    private final VoucherService voucherService;
    private final CustomPlanService customPlanService;
    private final SmsService smsService;
    private final PortalSettingsService portalSettings;

    // --- Settings ---

    @Transactional
    public LoyaltySettings settings() {
        return settingsRepo.findById(LoyaltySettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(LoyaltySettings.builder()
                        .id(LoyaltySettings.SINGLETON_ID).build()));
    }

    @Transactional
    public LoyaltySettings saveSettings(LoyaltySettings in) {
        LoyaltySettings s = settings();
        s.setEnabled(in.isEnabled());
        s.setPointsPerHundredKes(Math.max(0, in.getPointsPerHundredKes()));
        s.setPointsPerMinute(Math.max(1, in.getPointsPerMinute()));
        s.setMinRedeemMinutes(Math.max(1, in.getMinRedeemMinutes()));
        s.setMaxRedeemMinutes(Math.max(s.getMinRedeemMinutes(), in.getMaxRedeemMinutes()));
        return settingsRepo.save(s);
    }

    // --- Earning ---

    /** Awards points for a completed payment. Silent no-op when disabled. */
    @Transactional
    public void earn(String phoneNumber, BigDecimal amount) {
        LoyaltySettings s = settings();
        if (!s.isEnabled() || amount == null || phoneNumber == null || phoneNumber.isBlank()) {
            return;
        }
        long earned = amount.multiply(BigDecimal.valueOf(s.getPointsPerHundredKes()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValue();
        if (earned <= 0) {
            return;
        }
        LoyaltyAccount acct = account(phoneNumber);
        acct.setPoints(acct.getPoints() + earned);
        acct.setTotalEarned(acct.getTotalEarned() + earned);
        accounts.save(acct);
        log.info("Loyalty: {} earned {} point(s), balance {}", phoneNumber, earned, acct.getPoints());
    }

    // --- Reading ---

    @Transactional(readOnly = true)
    public Map<String, Object> balance(String phoneNumber) {
        LoyaltySettings s = settings();
        LoyaltyAccount acct = accounts.findById(phoneNumber).orElse(null);
        long points = acct == null ? 0 : acct.getPoints();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.isEnabled());
        out.put("points", points);
        out.put("pointsPerMinute", s.getPointsPerMinute());
        out.put("minRedeemMinutes", s.getMinRedeemMinutes());
        out.put("maxRedeemMinutes", s.getMaxRedeemMinutes());
        // The most free minutes this balance could buy right now.
        out.put("redeemableMinutes", points / s.getPointsPerMinute());
        return out;
    }

    // --- Redeeming ---

    /**
     * Spends points on free minutes and SMSes the voucher to the account's
     * phone. Throws with a readable reason on any problem; never returns the
     * code, so it can only reach the phone that owns the points.
     */
    @Transactional
    public Map<String, Object> redeem(String phoneNumber, int minutes) {
        LoyaltySettings s = settings();
        if (!s.isEnabled()) {
            throw new IllegalStateException("The rewards programme is not available right now");
        }
        if (!smsService.isEnabled()) {
            throw new IllegalStateException("Rewards can't be sent right now — please contact support");
        }
        if (minutes < s.getMinRedeemMinutes() || minutes > s.getMaxRedeemMinutes()) {
            throw new IllegalArgumentException(
                    "Choose between " + s.getMinRedeemMinutes() + " and " + s.getMaxRedeemMinutes() + " minutes");
        }
        long cost = (long) minutes * s.getPointsPerMinute();
        LoyaltyAccount acct = accounts.findById(phoneNumber).orElse(null);
        if (acct == null || acct.getPoints() < cost) {
            throw new IllegalStateException("Not enough points for that reward");
        }
        Voucher voucher = voucherService.issueCustom(
                customPlanService.systemPlan(customPlanService.settings()),
                phoneNumber, minutes, null, null, "loyalty");
        acct.setPoints(acct.getPoints() - cost);
        acct.setTotalRedeemed(acct.getTotalRedeemed() + cost);
        accounts.save(acct);

        String business = portalSettings.settings().getBusinessName();
        smsService.trySend(phoneNumber,
                "Your " + business + " reward: code " + voucher.getCode() + " for " + minutes
                        + " minutes of free WiFi. Enjoy!");
        log.info("Loyalty: {} redeemed {} point(s) for {} min", phoneNumber, cost, minutes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Sent! Your reward code is on its way by SMS.");
        out.put("pointsSpent", cost);
        out.put("remainingPoints", acct.getPoints());
        return out;
    }

    private LoyaltyAccount account(String phoneNumber) {
        return accounts.findById(phoneNumber)
                .orElseGet(() -> LoyaltyAccount.builder().phoneNumber(phoneNumber).build());
    }
}
