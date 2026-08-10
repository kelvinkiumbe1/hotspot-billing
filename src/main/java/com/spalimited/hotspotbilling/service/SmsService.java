package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.OutboundMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sends SMS through Africa's Talking. Numbers are stored as 2547XXXXXXXX
 * and converted to +254 international format on send. Batches of up to
 * 100 recipients per API call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsService {

    private static final int BATCH_SIZE = 100;

    private final WhatsappService whatsappService;
    private final OutboxService outboxService;
    private final MessagingSettingsService messagingSettings;
    private final HttpClient http = HttpClient.newHttpClient();

    public boolean isEnabled() {
        var cfg = messagingSettings.sms();
        return cfg.enabled()
                && cfg.username() != null && !cfg.username().isBlank()
                && cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    /** Sends to many recipients; returns how many numbers were submitted. */
    public int sendBulk(Collection<String> phoneNumbers, String message) {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "SMS is not configured — set SMS_ENABLED, SMS_USERNAME and SMS_API_KEY");
        }
        List<String> numbers = phoneNumbers.stream()
                .filter(p -> p != null && p.matches("254\\d{9}"))
                .distinct()
                .map(p -> "+" + p)
                .collect(Collectors.toList());
        int sent = 0;
        for (int i = 0; i < numbers.size(); i += BATCH_SIZE) {
            List<String> batch = numbers.subList(i, Math.min(i + BATCH_SIZE, numbers.size()));
            dispatch(String.join(",", batch), message);
            sent += batch.size();
        }
        return sent;
    }

    /**
     * Best-effort single send that never throws. Tries WhatsApp first when
     * it is configured (cheaper and richer), then falls back to SMS. Every
     * attempt is written to the outbox, including the ones that fail and the
     * ones skipped because nothing is configured, so the log is the whole
     * picture rather than only the successes.
     */
    public void trySend(String phoneNumber, String message) {
        trySend(phoneNumber, message, null, null, null);
    }

    /** As above, tagged with a campaign reference and the sender. */
    public void trySend(String phoneNumber, String message, String recipientName,
                        String campaignRef, String sentBy) {
        if (whatsappService != null && whatsappService.isEnabled()) {
            boolean ok = whatsappService.send("+" + phoneNumber, message);
            outboxService.record(OutboundMessage.Channel.WHATSAPP, phoneNumber, recipientName,
                    message, ok, ok ? null : "WhatsApp gateway rejected the message",
                    campaignRef, sentBy);
            if (ok) {
                return;
            }
            // Fall through and try SMS rather than giving up on the customer.
        }
        if (!isEnabled()) {
            log.info("SMS disabled — would have sent to {}: {}", phoneNumber, message);
            outboxService.record(OutboundMessage.Channel.SMS, phoneNumber, recipientName, message,
                    false, "SMS is not configured", campaignRef, sentBy);
            return;
        }
        try {
            sendBulk(List.of(phoneNumber), message);
            outboxService.record(OutboundMessage.Channel.SMS, phoneNumber, recipientName, message,
                    true, null, campaignRef, sentBy);
        } catch (Exception e) {
            log.warn("SMS to {} failed: {}", phoneNumber, e.getMessage());
            outboxService.record(OutboundMessage.Channel.SMS, phoneNumber, recipientName, message,
                    false, e.getMessage(), campaignRef, sentBy);
        }
    }

    private void dispatch(String to, String message) {
        var cfg = messagingSettings.sms();
        if ("TWILIO".equalsIgnoreCase(cfg.provider())) {
            dispatchTwilio(cfg, to, message);
        } else {
            dispatchAfricasTalking(cfg, to, message);
        }
    }

    private void dispatchAfricasTalking(MessagingSettingsService.SmsConfig cfg, String to, String message) {
        try {
            StringBuilder form = new StringBuilder()
                    .append("username=").append(encode(cfg.username()))
                    .append("&to=").append(encode(to))
                    .append("&message=").append(encode(message));
            if (cfg.senderId() != null && !cfg.senderId().isBlank()) {
                form.append("&from=").append(encode(cfg.senderId()));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/version1/messaging"))
                    .header("apiKey", cfg.apiKey())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("SMS gateway returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            log.info("SMS batch submitted ({} chars to {} numbers)", message.length(), to.split(",").length);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SMS send failed: " + e.getMessage(), e);
        }
    }

    /**
     * Twilio's Messages API sends to one recipient per request, so a batch is
     * split and sent individually. Auth is HTTP Basic with the Account SID and
     * Auth Token (stored in the username/apiKey fields); the From number is the
     * sender-id field.
     */
    private void dispatchTwilio(MessagingSettingsService.SmsConfig cfg, String to, String message) {
        String sid = cfg.username() == null ? "" : cfg.username().trim();
        String basic = Base64.getEncoder().encodeToString(
                (sid + ":" + (cfg.apiKey() == null ? "" : cfg.apiKey())).getBytes(StandardCharsets.UTF_8));
        String url = "https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json";
        for (String raw : to.split(",")) {
            String number = raw.trim();
            if (number.isEmpty()) {
                continue;
            }
            try {
                String form = "To=" + encode(number)
                        + "&From=" + encode(cfg.senderId() == null ? "" : cfg.senderId())
                        + "&Body=" + encode(message);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Basic " + basic)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    throw new IllegalStateException("Twilio returned HTTP " + response.statusCode()
                            + ": " + response.body());
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Twilio send failed: " + e.getMessage(), e);
            }
        }
        log.info("Twilio SMS sent ({} chars to {} numbers)", message.length(), to.split(",").length);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
