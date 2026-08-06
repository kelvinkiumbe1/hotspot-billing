package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.SmsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    private final SmsProperties props;
    private final HttpClient http = HttpClient.newHttpClient();

    public boolean isEnabled() {
        return props.enabled()
                && props.username() != null && !props.username().isBlank()
                && props.apiKey() != null && !props.apiKey().isBlank();
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

    /** Best-effort single send that never throws (e.g. voucher code after purchase). */
    public void trySend(String phoneNumber, String message) {
        if (!isEnabled()) {
            log.info("SMS disabled — would have sent to {}: {}", phoneNumber, message);
            return;
        }
        try {
            sendBulk(List.of(phoneNumber), message);
        } catch (Exception e) {
            log.warn("SMS to {} failed: {}", phoneNumber, e.getMessage());
        }
    }

    private void dispatch(String to, String message) {
        try {
            StringBuilder form = new StringBuilder()
                    .append("username=").append(encode(props.username()))
                    .append("&to=").append(encode(to))
                    .append("&message=").append(encode(message));
            if (props.senderId() != null && !props.senderId().isBlank()) {
                form.append("&from=").append(encode(props.senderId()));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl() + "/version1/messaging"))
                    .header("apiKey", props.apiKey())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("SMS gateway returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            log.info("SMS batch submitted ({} chars to {})", message.length(), to.split(",").length + " numbers");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SMS send failed: " + e.getMessage(), e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
