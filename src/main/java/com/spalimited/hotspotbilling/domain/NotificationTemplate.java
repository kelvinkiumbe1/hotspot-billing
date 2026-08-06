package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An editable SMS/WhatsApp message body. Placeholders like {code} and
 * {amount} are substituted at send time, so wording and language can be
 * changed from the admin without a redeploy.
 */
@Entity
@Table(name = "notification_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationTemplate {

    /** Every message the system sends on its own. */
    public enum Key {
        VOUCHER_ISSUED,
        TRIAL_ISSUED,
        SUBSCRIPTION_PAID,
        EXPIRY_REMINDER,
        SUBSCRIPTION_SUSPENDED,
        SUBSCRIPTION_EXTENDED
    }

    @Id
    @Enumerated(EnumType.STRING)
    private Key templateKey;

    @Column(nullable = false, length = 640)
    private String body;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    private Instant updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = Instant.now();
    }
}
