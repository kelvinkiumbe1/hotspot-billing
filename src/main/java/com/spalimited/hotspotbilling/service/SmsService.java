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
                .map(SmsService::normalise)
                .filter(java.util.Objects::nonNull)
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
    public void trySend(String rawPhoneNumber, String message, String recipientName,
                        String campaignRef, String sentBy) {
        // Numbers reach this method from wherever they were typed: a customer
        // filling in a support form, an operator adding a technician, an
        // imported spreadsheet. The gateways accept exactly 2547XXXXXXXX and
        // discard anything else without a word, so a number entered the way
        // people actually say it — "0757…" — used to mean the message was
        // never sent and nobody found out. Normalising at the one place every
        // send passes through is the only version of this fix that stays fixed.
        String phoneNumber = normalise(rawPhoneNumber);
        if (phoneNumber == null) {
            log.warn("Not sending: '{}' is not a usable Kenyan mobile number", rawPhoneNumber);
            outboxService.record(OutboundMessage.Channel.SMS, rawPhoneNumber, recipientName, message,
                    false, "Not a usable phone number", campaignRef, sentBy);
            return;
        }
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
            log.info("No messaging channel configured — would have sent to {}: {}", phoneNumber, message);
            // Naming only SMS here sent people to the wrong settings page. This
            // path is reached when WhatsApp is unavailable *as well*, and for a
            // code that should appear in a WhatsApp chat, WhatsApp is the fix.
            outboxService.record(OutboundMessage.Channel.SMS, phoneNumber, recipientName, message,
                    false, "No WhatsApp or SMS gateway is configured", campaignRef, sentBy);
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

    /**
     * A Kenyan mobile number as the gateways want it, however it was typed:
     * {@code 0757306837}, {@code +254 757 306 837} and {@code 757306837} are
     * the same phone. Null when it cannot be one — better a recorded failure
     * than a message posted into the void.
     */
    static String normalise(String raw) {
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
