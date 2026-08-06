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
    private static final Map<NotificationTemplate.Key, String> DEFAULTS = Map.of(
            NotificationTemplate.Key.VOUCHER_ISSUED,
            "Your {business} access code is {code}. Use it as both WiFi username and password. Thank you!",
            NotificationTemplate.Key.TRIAL_ISSUED,
            "Welcome to {business}! Your free trial code is {code}, valid for {minutes} minutes.",
            NotificationTemplate.Key.SUBSCRIPTION_PAID,
            "Payment received. Your {business} home internet is active until {date}. Thank you!",
            NotificationTemplate.Key.EXPIRY_REMINDER,
            "Reminder: your {business} home internet expires on {date}. Pay KES {amount} to stay connected: {payUrl}",
            NotificationTemplate.Key.SUBSCRIPTION_SUSPENDED,
            "Your {business} home internet has been suspended because the subscription expired. "
                    + "Pay KES {amount} to reconnect instantly: {payUrl}",
            NotificationTemplate.Key.SUBSCRIPTION_EXTENDED,
            "Good news! Your {business} home internet has been extended until {date}.");

    private final NotificationTemplateRepository templates;
    private final SmsService smsService;

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
        for (Map.Entry<String, String> entry : values.entrySet()) {
            body = body.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        smsService.trySend(phoneNumber, body);
    }
}
