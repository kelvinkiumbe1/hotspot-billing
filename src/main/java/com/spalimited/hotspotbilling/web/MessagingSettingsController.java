package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.MessagingSettings;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import com.spalimited.hotspotbilling.service.SmsService;
import com.spalimited.hotspotbilling.service.WhatsappService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/** The operator's own SMS and WhatsApp accounts. */
@RestController
@RequestMapping("/api/admin/settings/messaging")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class MessagingSettingsController {

    private final MessagingSettingsService settingsService;
    private final SmsService smsService;
    private final WhatsappService whatsappService;
    private final AuditService audit;
    private final com.spalimited.hotspotbilling.service.TelegramService telegramService;

    @GetMapping
    public Map<String, Object> get() {
        return settingsService.describe();
    }

    public record MessagingRequest(
            boolean smsEnabled,
            String smsProvider,
            String smsUsername,
            String smsApiKey,
            String smsSenderId,
            boolean whatsappEnabled,
            String whatsappPhoneNumberId,
            String whatsappAccessToken,
            String whatsappAppSecret,
            @Pattern(regexp = "|254\\d{9}", message = "Use the 2547XXXXXXXX form, or leave it blank")
            String alertPhone,
            boolean telegramEnabled,
            String telegramBotToken,
            /* A group chat id is negative. Deliberately not pattern-matched as a
               number: a rule rejecting the minus sign would reject exactly the
               case people get wrong, and they would blame the field. */
            String telegramChatId) {
    }

    @PutMapping
    public Map<String, Object> save(@Valid @RequestBody MessagingRequest request, Principal principal) {
        settingsService.save(MessagingSettings.builder()
                .smsEnabled(request.smsEnabled())
                .smsProvider(request.smsProvider())
                .smsUsername(request.smsUsername())
                .smsApiKey(request.smsApiKey())
                .smsSenderId(request.smsSenderId())
                .whatsappEnabled(request.whatsappEnabled())
                .whatsappPhoneNumberId(request.whatsappPhoneNumberId())
                .whatsappAccessToken(request.whatsappAccessToken())
                .whatsappAppSecret(request.whatsappAppSecret())
                .alertPhone(request.alertPhone())
                .telegramEnabled(request.telegramEnabled())
                .telegramBotToken(request.telegramBotToken())
                .telegramChatId(request.telegramChatId())
                .build(), principal.getName());
        audit.record(principal, "messaging.settings", "Updated messaging gateways");
        return settingsService.describe();
    }

    /**
     * Posts a test line to the Telegram chat.
     *
     * <p>Worth its own button because every failure here is configuration with a
     * distinct cause -- a wrong token, a bot never added to the group, a positive
     * chat id where a negative one was needed -- and the service names which.
     */
    @PostMapping("/telegram/test")
    public Map<String, Object> testTelegram(Principal principal) {
        String result = telegramService.test();
        audit.record(principal, "messaging.telegram.test", result);
        return Map.of("message", result);
    }

    public record TestRequest(
            @com.spalimited.hotspotbilling.config.Phone String phoneNumber) {
    }

    /**
     * Sends one real message so the operator finds out the credentials work
     * now, rather than when a customer fails to get their voucher. It goes
     * through the same path as every other message, so the result lands in
     * the outbox alongside them.
     */
    @PostMapping("/test")
    public Map<String, Object> test(@Valid @RequestBody TestRequest request, Principal principal) {
        if (!smsService.isEnabled() && !whatsappService.isEnabled()) {
            throw new IllegalStateException("Turn on and fill in at least one gateway first");
        }
        smsService.trySend(request.phoneNumber(),
                "Test message from your billing system. If you can read this, messaging is working.",
                null, "TEST", principal.getName());
        audit.record(principal, "messaging.test", "Sent a test message to " + request.phoneNumber());
        return Map.of("message",
                "Sent. Check the phone, and the Outbox for whether the gateway accepted it.");
    }
}
