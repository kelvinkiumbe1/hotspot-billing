package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends the one email that matters here: telling a new owner their account is
 * ready and where to sign in. Best-effort — if SMTP isn't set up, or a send
 * fails, it's logged and swallowed so provisioning is never held up by mail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSender;

    @Value("${zidi.mail.enabled:false}")
    private boolean enabled;

    @Value("${zidi.mail.from}")
    private String from;

    public void sendAccountReady(Tenant tenant) {
        if (!enabled) {
            log.info("Mail disabled — would tell {} their account {} is ready at {}",
                    tenant.getOwnerEmail(), tenant.getSlug(), tenant.getUrl());
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("zidi.mail.enabled is true but no mail sender is configured (set MAIL_HOST)");
            return;
        }
        String hi = tenant.getOwnerName() == null || tenant.getOwnerName().isBlank()
                ? "Hi" : "Hi " + tenant.getOwnerName().split(" ")[0];
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(tenant.getOwnerEmail());
            msg.setSubject("Your Zidi account is ready");
            msg.setText(hi + ",\n\n"
                    + "Your Zidi account for " + tenant.getBusinessName() + " is ready.\n\n"
                    + "Sign in:  " + tenant.getUrl() + "/admin\n"
                    + "Use this email and the password you chose at signup. You can turn on\n"
                    + "fingerprint / face sign-in any time from Password & security.\n\n"
                    + "Your first 14 days are free — set up your plans and M-Pesa whenever you're ready.\n\n"
                    + "— The Zidi team");
            sender.send(msg);
            log.info("Sent account-ready email to {}", tenant.getOwnerEmail());
        } catch (Exception e) {
            log.warn("Could not send account-ready email to {}: {}", tenant.getOwnerEmail(), e.getMessage());
        }
    }
}
