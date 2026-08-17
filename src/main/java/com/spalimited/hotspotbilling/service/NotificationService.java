package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Sends the system's automatic messages using admin-editable templates.
 * Placeholders in {braces} are replaced with the supplied values.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    /** Shipped wording, seeded on first use and editable thereafter. */
    private static final Map<NotificationTemplate.Key, String> DEFAULTS = Map.ofEntries(
            Map.entry(NotificationTemplate.Key.HOTSPOT_EXPIRY_NUDGE,
                    "Heads up — your {business} WiFi runs out in about {minutes} min. "
                            + "Reply to this message to buy more time and stay online. No need to log in again."),
            Map.entry(NotificationTemplate.Key.HOTSPOT_DATA_NUDGE,
                    "You've used most of your {business} data — {usedMb}MB of {capMb}MB. "
                            + "Reply to this message to top up and stay online."),
            Map.entry(NotificationTemplate.Key.FUP_NOTICE,
                    "You've reached your {business} fair-use limit of {capMb}MB. "
                            + "Reply to top up and keep browsing at full speed."),
            Map.entry(NotificationTemplate.Key.DUNNING_RETRY,
                    "We couldn't complete your {business} renewal yet. We've sent a fresh M-Pesa request "
                            + "for {currency} {amount} — enter your PIN to stay connected, or pay here: {payUrl}"),
            Map.entry(NotificationTemplate.Key.VOUCHER_ISSUED,
                    "Your {business} access code is {code}. Use it as both WiFi username and password. Thank you!"),
            Map.entry(NotificationTemplate.Key.TRIAL_ISSUED,
                    "Welcome to {business}! Your free trial code is {code}, valid for {minutes} minutes."),
            Map.entry(NotificationTemplate.Key.SUBSCRIPTION_PAID,
                    "Payment received. Your {business} home internet is active until {date}. Thank you!"),
            Map.entry(NotificationTemplate.Key.EXPIRY_REMINDER,
                    "Reminder: your {business} home internet expires on {date}. Pay {currency} {amount} to stay connected: {payUrl}"),
            Map.entry(NotificationTemplate.Key.SUBSCRIPTION_SUSPENDED,
                    "Your {business} home internet has been suspended because the subscription expired. "
                            + "Pay {currency} {amount} to reconnect instantly: {payUrl}"),
            Map.entry(NotificationTemplate.Key.SUBSCRIPTION_EXTENDED,
                    "Good news! Your {business} home internet has been extended until {date}."),
            Map.entry(NotificationTemplate.Key.WINBACK_FIRST,
                    "We miss you at {business}! Your internet's been off since {date}. "
                            + "Come back today — pay {currency} {amount} here: {payUrl}"),
            Map.entry(NotificationTemplate.Key.WINBACK_SECOND,
                    "Still saving your spot at {business}. Reconnect now and stay online — "
                            + "pay {currency} {amount}: {payUrl}"),
            Map.entry(NotificationTemplate.Key.WINBACK_FINAL,
                    "Last call from {business} — we'd love to have you back. "
                            + "Reconnect today: {payUrl}"));

    private final NotificationTemplateRepository templates;
    private final SmsService smsService;
    private final MoneyService money;

    @Transactional
    public List<NotificationTemplate> all() {
        DEFAULTS.forEach((key, body) -> {
            if (templates.findById(key).isEmpty()) {
                templates.save(NotificationTemplate.builder().templateKey(key).body(body).build());
            }
        });
        return templates.findAll();
    }

    @Transactional
    public NotificationTemplate update(NotificationTemplate.Key key, String body, boolean enabled) {
        NotificationTemplate template = templates.findById(key)
                .orElseGet(() -> NotificationTemplate.builder().templateKey(key).build());
        template.setBody(body);
        template.setEnabled(enabled);
        return templates.save(template);
    }

    /** Renders the template and sends it; silently skips if disabled. */
    @Transactional(readOnly = true)
    public void send(NotificationTemplate.Key key, String phoneNumber, Map<String, String> values) {
        NotificationTemplate template = templates.findById(key).orElse(null);
        String body = template != null ? template.getBody() : DEFAULTS.get(key);
        if (template != null && !template.isEnabled()) {
            log.debug("Template {} is disabled — not sending", key);
            return;
        }
        if (body == null) {
            return;
        }
        // Every template gets {currency} for free, so an operator can localise
        // the wording without the caller knowing anything about money. Templates
        // already stored keep whatever they say — an operator in Nairobi wrote
        // "KES" and is right; only the shipped defaults changed.
        if (!values.containsKey("currency")) {
            values = new java.util.LinkedHashMap<>(values);
            values.put("currency", money.code());
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            body = body.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        smsService.trySend(phoneNumber, body);
    }
}
