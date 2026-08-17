package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The operator's own SMS and WhatsApp accounts, held as a single row.
 *
 * <p>These used to be environment variables, which meant whoever hosts the
 * deployment had to edit a file and restart before an operator could send
 * anything — and had to be trusted with their gateway credentials. Same
 * reasoning as {@link PaymentGateway}: the people whose account it is
 * should be able to set it up themselves.
 */
@Entity
@Table(name = "messaging_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessagingSettings {

    @Id
    private Long id;

    // --- SMS (Africa's Talking) ---

    @Builder.Default
    @Column(nullable = false)
    private boolean smsEnabled = false;

    /** Which gateway sends the SMS: AFRICASTALKING or TWILIO. */
    @Builder.Default
    @Column(nullable = false, length = 40)
    private String smsProvider = "AFRICASTALKING";

    /** Africa's Talking username, or the Twilio Account SID. */
    private String smsUsername;

    /** Africa's Talking API key, or the Twilio Auth Token. */
    private String smsApiKey;

    /** Africa's Talking sender ID, or the Twilio "From" number. */
    private String smsSenderId;

    // --- WhatsApp (Meta Cloud API) ---

    @Builder.Default
    @Column(nullable = false)
    private boolean whatsappEnabled = false;

    private String whatsappPhoneNumberId;

    @Column(length = 1000)
    private String whatsappAccessToken;

    /**
     * Meta app secret, used to verify that an inbound webhook really came from
     * Meta. Without it the "from" number on a message is only a claim, and the
     * bots behind it act on that claim.
     */
    @Column(length = 200)
    private String whatsappAppSecret;

    /**
     * The string Meta echoes back during the webhook handshake. Not a secret
     * in the usual sense — both ends simply have to hold the same one — so it
     * is shown in full for copying rather than masked.
     */
    @Column(length = 120)
    private String whatsappVerifyToken;

    @jakarta.persistence.Transient
    public boolean isInboundVerifiable() {
        return whatsappAppSecret != null && !whatsappAppSecret.isBlank();
    }

    /** Phone that receives router-offline alerts and daily digests. */
    private String alertPhone;

    private String updatedBy;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = Instant.now();
    }

    @Transient
    public boolean isSmsConfigured() {
        return smsEnabled && filled(smsUsername) && filled(smsApiKey);
    }

    @Transient
    public boolean isWhatsappConfigured() {
        return whatsappEnabled && filled(whatsappPhoneNumberId) && filled(whatsappAccessToken);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
