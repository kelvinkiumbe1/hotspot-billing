package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * One place operator alerts go out from.
 *
 * <p>Nine services each had their own three-line version of this: read the alert
 * phone, send an SMS. That was fine until there was a second channel, at which
 * point adding it meant nine edits and one of them being missed -- and the one
 * missed is discovered on the night it mattered.
 *
 * <p>Both channels, deliberately. Telegram is free and threaded and reaches the
 * laptop somebody is sitting at; SMS is the one channel that still works when the
 * internet is the thing that broke, which is exactly when these alerts matter.
 * Sending to both costs one text and is the difference between an alert arriving
 * and an alert existing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperatorAlertService {

    private final MessagingSettingsService messagingSettings;
    private final SmsService smsService;
    private final TelegramService telegramService;

    /**
     * Tells the operator something.
     *
     * <p>Never throws. Every caller is already reporting a problem, and an
     * alerting failure must not become the exception that hides it.
     */
    public void alert(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        boolean anywhere = false;

        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            try {
                smsService.trySend(phone, message);
                anywhere = true;
            } catch (Exception e) {
                log.warn("Could not SMS the operator alert: {}", e.getMessage());
            }
        }
        if (telegramService.send(message)) {
            anywhere = true;
        }

        if (!anywhere) {
            // The log is the last channel. An alert with nowhere to go is itself
            // worth recording, because the silence is otherwise indistinguishable
            // from nothing having happened.
            log.warn("Operator alert had nowhere to go — no alert phone and no Telegram: {}",
                    message);
        }
    }
}
