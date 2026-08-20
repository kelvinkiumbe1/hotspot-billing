package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Operator alerts in a Telegram chat.
 *
 * <p>Alerts went out by SMS to one number: money per alert, the same inbox as
 * everything else, and on a bad night thirty texts nobody reads. Telegram is
 * free, threaded, and reachable from the laptop somebody is sitting at when they
 * act on an alert.
 *
 * <p>SMS is kept alongside rather than replaced. It is the one channel that still
 * works when the internet is the thing that broke, which is precisely when these
 * alerts matter most -- so a Telegram-only setup would go quiet at the worst
 * possible moment.
 *
 * <p>Raw HTTP against the Bot API. It is one form-encoded POST and adding a
 * Telegram client library for it would be a dependency for a URL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramService {

    private static final String BASE = "https://api.telegram.org";

    private final MessagingSettingsService messagingSettings;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    /** Whether a chat is configured to post to. */
    public boolean usable() {
        var cfg = messagingSettings.settings();
        return cfg.isTelegramEnabled()
                && cfg.getTelegramBotToken() != null && !cfg.getTelegramBotToken().isBlank()
                && cfg.getTelegramChatId() != null && !cfg.getTelegramChatId().isBlank();
    }

    /**
     * Posts a message, and says whether it worked.
     *
     * <p>Never throws. This is an alerting channel: something has already gone
     * wrong by the time it is called, and a failure here must not become the
     * exception that stops the job reporting the original problem.
     */
    public boolean send(String text) {
        if (!usable()) {
            return false;
        }
        var cfg = messagingSettings.settings();
        try {
            String form = "chat_id=" + enc(cfg.getTelegramChatId())
                    + "&text=" + enc(text)
                    // Plain text on purpose. Alert messages carry router names,
                    // customer names and error strings, and any of those
                    // containing an underscore or an asterisk would break
                    // Markdown parsing and be rejected outright -- so the one
                    // message you most needed would be the one that failed.
                    + "&disable_web_page_preview=true";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/bot" + cfg.getTelegramBotToken().trim()
                            + "/sendMessage"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Telegram refused the message (HTTP {}): {}",
                        response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Could not post to Telegram: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Sends a test message and reports what happened in words.
     *
     * <p>The failures here are all configuration and all have distinct causes
     * worth naming: a wrong token is a 401, a chat the bot was never added to is
     * a 400, and a positive chat id where a negative one was needed is the
     * mistake everybody makes first.
     */
    public String test() {
        var cfg = messagingSettings.settings();
        if (cfg.getTelegramBotToken() == null || cfg.getTelegramBotToken().isBlank()) {
            return "No bot token. Talk to @BotFather in Telegram to make a bot.";
        }
        if (cfg.getTelegramChatId() == null || cfg.getTelegramChatId().isBlank()) {
            return "No chat id. Add the bot to your group, then read the id from "
                    + BASE + "/bot<token>/getUpdates — a group id starts with a minus sign.";
        }
        try {
            String form = "chat_id=" + enc(cfg.getTelegramChatId())
                    + "&text=" + enc("Zidi is connected. Alerts will arrive here.");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/bot" + cfg.getTelegramBotToken().trim()
                            + "/sendMessage"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                return "Telegram rejected the token. Check it was copied whole — it has a "
                        + "colon in the middle and both halves matter.";
            }
            if (response.statusCode() == 400 && response.body().contains("chat not found")) {
                return "That chat does not exist, or the bot has not been added to it. A "
                        + "group id starts with a minus sign; a positive number is a "
                        + "personal chat and the bot cannot open one first.";
            }
            if (response.statusCode() >= 300) {
                return "Telegram said: " + response.body();
            }
            return "Sent. Check the chat.";
        } catch (Exception e) {
            return "Could not reach Telegram: " + e.getMessage();
        }
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
