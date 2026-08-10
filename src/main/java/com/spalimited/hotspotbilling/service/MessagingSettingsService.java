package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.SmsProperties;
import com.spalimited.hotspotbilling.config.WhatsappProperties;
import com.spalimited.hotspotbilling.domain.MessagingSettings;
import com.spalimited.hotspotbilling.repository.MessagingSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Effective messaging configuration: whatever the operator saved in the
 * admin, falling back to environment variables so deployments set up
 * before this existed keep sending.
 */
@Service
@RequiredArgsConstructor
public class MessagingSettingsService {

    private static final long ROW_ID = 1L;

    private final MessagingSettingsRepository repository;
    private final SmsProperties smsProps;
    private final WhatsappProperties whatsappProps;

    @Value("${app.alert-phone:}")
    private String alertPhoneFallback;

    public record SmsConfig(boolean enabled, String provider, String username, String apiKey,
                            String senderId, String baseUrl) {
    }

    public record WhatsappConfig(boolean enabled, String phoneNumberId,
                                 String accessToken, String baseUrl) {
    }

    @Transactional
    public MessagingSettings settings() {
        return repository.findById(ROW_ID)
                .orElseGet(() -> repository.save(MessagingSettings.builder().id(ROW_ID).build()));
    }

    @Transactional(readOnly = true)
    public SmsConfig sms() {
        MessagingSettings s = repository.findById(ROW_ID).orElse(null);
        if (s != null && s.isSmsConfigured()) {
            return new SmsConfig(true, s.getSmsProvider(), s.getSmsUsername(), s.getSmsApiKey(),
                    s.getSmsSenderId(), smsProps.baseUrl());
        }
        return new SmsConfig(smsProps.enabled(), "AFRICASTALKING", smsProps.username(), smsProps.apiKey(),
                smsProps.senderId(), smsProps.baseUrl());
    }

    @Transactional(readOnly = true)
    public WhatsappConfig whatsapp() {
        MessagingSettings s = repository.findById(ROW_ID).orElse(null);
        if (s != null && s.isWhatsappConfigured()) {
            return new WhatsappConfig(true, s.getWhatsappPhoneNumberId(),
                    s.getWhatsappAccessToken(), whatsappProps.baseUrl());
        }
        return new WhatsappConfig(whatsappProps.enabled(), whatsappProps.phoneNumberId(),
                whatsappProps.accessToken(), whatsappProps.baseUrl());
    }

    /** Where router-offline alerts go. */
    @Transactional(readOnly = true)
    public String alertPhone() {
        MessagingSettings s = repository.findById(ROW_ID).orElse(null);
        if (s != null && s.getAlertPhone() != null && !s.getAlertPhone().isBlank()) {
            return s.getAlertPhone();
        }
        return alertPhoneFallback;
    }

    /** Current state with credentials masked, for the settings screen. */
    @Transactional
    public Map<String, Object> describe() {
        MessagingSettings s = settings();
        SmsConfig sms = sms();
        WhatsappConfig wa = whatsapp();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("smsEnabled", s.isSmsEnabled());
        out.put("smsProvider", s.getSmsProvider());
        out.put("smsUsername", s.getSmsUsername());
        out.put("smsApiKey", mask(s.getSmsApiKey()));
        out.put("smsSenderId", s.getSmsSenderId());
        out.put("smsWorking", sms.enabled() && notBlank(sms.username()) && notBlank(sms.apiKey()));

        out.put("whatsappEnabled", s.isWhatsappEnabled());
        out.put("whatsappPhoneNumberId", s.getWhatsappPhoneNumberId());
        out.put("whatsappAccessToken", mask(s.getWhatsappAccessToken()));
        out.put("whatsappWorking", wa.enabled() && notBlank(wa.phoneNumberId()) && notBlank(wa.accessToken()));

        out.put("alertPhone", s.getAlertPhone());
        out.put("updatedAt", s.getUpdatedAt());
        out.put("updatedBy", s.getUpdatedBy());

        // Says plainly when sending only works because of a variable set at
        // deploy time, so nobody wonders why the form looks empty.
        out.put("usingEnvironmentFallback",
                (!s.isSmsConfigured() && sms.enabled()) || (!s.isWhatsappConfigured() && wa.enabled()));
        return out;
    }

    @Transactional
    public MessagingSettings save(MessagingSettings incoming, String updatedBy) {
        MessagingSettings s = settings();
        s.setSmsEnabled(incoming.isSmsEnabled());
        String provider = incoming.getSmsProvider();
        s.setSmsProvider("TWILIO".equalsIgnoreCase(provider) ? "TWILIO" : "AFRICASTALKING");
        s.setSmsUsername(trim(incoming.getSmsUsername()));
        s.setSmsSenderId(trim(incoming.getSmsSenderId()));
        // A blank secret means "keep what is stored" — it can no longer be
        // read back, so requiring a re-entry to change the sender ID would
        // be a trap.
        if (notBlank(incoming.getSmsApiKey()) && !incoming.getSmsApiKey().startsWith("••••")) {
            s.setSmsApiKey(incoming.getSmsApiKey().trim());
        }

        s.setWhatsappEnabled(incoming.isWhatsappEnabled());
        s.setWhatsappPhoneNumberId(trim(incoming.getWhatsappPhoneNumberId()));
        if (notBlank(incoming.getWhatsappAccessToken())
                && !incoming.getWhatsappAccessToken().startsWith("••••")) {
            s.setWhatsappAccessToken(incoming.getWhatsappAccessToken().trim());
        }

        s.setAlertPhone(trim(incoming.getAlertPhone()));
        s.setUpdatedBy(updatedBy);
        return repository.save(s);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String tail = secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
        return "••••••••" + tail;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
