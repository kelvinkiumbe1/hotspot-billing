package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.EmailSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Sends plain-text email through the operator's own SMTP server. The
 * sender is built fresh from the saved settings on each send rather than
 * from static Spring properties, so credentials entered in the admin take
 * effect immediately without a restart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final EmailSettingsService settings;

    public boolean isEnabled() {
        return settings.get().isConfigured();
    }

    /** Best-effort send that never throws; returns whether it went out. */
    public boolean trySend(String to, String subject, String body) {
        try {
            send(to, subject, body);
            return true;
        } catch (Exception e) {
            log.warn("Email to {} failed: {}", to, e.getMessage());
            return false;
        }
    }

    /** Sends one message, throwing with a readable reason on failure. */
    public void send(String to, String subject, String body) {
        EmailSettings s = settings.get();
        if (!s.isConfigured()) {
            throw new IllegalStateException("Email is not configured — set the SMTP host and from address");
        }
        JavaMailSenderImpl sender = build(s);
        SimpleMailMessage msg = new SimpleMailMessage();
        String from = (s.getFromName() != null && !s.getFromName().isBlank())
                ? s.getFromName() + " <" + s.getFromAddress() + ">"
                : s.getFromAddress();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            sender.send(msg);
            log.info("Email sent to {} ({})", to, subject);
        } catch (Exception e) {
            throw new IllegalStateException("Email send failed: " + e.getMessage(), e);
        }
    }

    private JavaMailSenderImpl build(EmailSettings s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.getHost());
        sender.setPort(s.getPort());
        if (s.getUsername() != null && !s.getUsername().isBlank()) {
            sender.setUsername(s.getUsername());
        }
        if (s.getPassword() != null && !s.getPassword().isBlank()) {
            sender.setPassword(s.getPassword());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        boolean auth = s.getUsername() != null && !s.getUsername().isBlank();
        props.put("mail.smtp.auth", String.valueOf(auth));
        if (s.isStartTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        } else {
            // Implicit TLS (typically port 465).
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
