package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * A once-a-day summary of the day's takings, sent to the operator by SMS
 * (and email, if configured). Runs hourly and fires only in the chosen
 * hour, remembering the last day it sent so a restart can't double-send.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalesDigestService {

    private final PaymentRepository payments;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final OperatorAlertSettingsService alertSettings;
    private final MessagingSettingsService messagingSettings;
    private final EmailSettingsService emailSettings;
    private final SmsService smsService;
    private final EmailService emailService;
    private final PortalSettingsService portalSettings;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void maybeSend() {
        OperatorAlertSettings s = alertSettings.get();
        if (!s.isSalesDigestEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        int hour = java.time.LocalTime.now(ZoneId.systemDefault()).getHour();
        if (hour != s.getSalesDigestHour() || today.equals(s.getLastDigestSent())) {
            return;
        }
        buildAndSend();
        alertSettings.markDigestSent(today);
    }

    /** Builds today's summary, sends it, and returns the text for a preview. */
    @Transactional(readOnly = true)
    public String buildAndSend() {
        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();

        BigDecimal hotspotAmount = payments.sumAmountByStatusSince(Payment.Status.SUCCESS, startOfDay);
        long hotspotCount = payments.countByStatusAndCompletedAtAfter(Payment.Status.SUCCESS, startOfDay);
        BigDecimal subsAmount = subscriptionPayments.sumAmountByStatusSince(SubscriptionPayment.Status.SUCCESS, startOfDay);
        long subsCount = subscriptionPayments.countByStatusAndCompletedAtAfter(SubscriptionPayment.Status.SUCCESS, startOfDay);
        BigDecimal total = hotspotAmount.add(subsAmount);

        String business = portalSettings.settings().getBusinessName();
        String message = business + " — today's sales\n"
                + "Total: KES " + total.toPlainString() + "\n"
                + "Hotspot: " + hotspotCount + " sale(s), KES " + hotspotAmount.toPlainString() + "\n"
                + "Subscriptions: " + subsCount + " payment(s), KES " + subsAmount.toPlainString();

        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, message);
        }
        String to = emailSettings.get().getFromAddress();
        if (emailService.isEnabled() && to != null && !to.isBlank()) {
            emailService.trySend(to, business + " — daily sales digest", message);
        }
        log.info("Sales digest sent (total KES {})", total.toPlainString());
        return message;
    }
}
