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
@IdClass(NotificationTemplate.TemplateId.class)
public class NotificationTemplate {

    /** The composite key: one wording per message, per language. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class TemplateId implements java.io.Serializable {
        private Key templateKey;
        private String language;
    }

    /** Every message the system sends on its own. */
    public enum Key {
        VOUCHER_ISSUED,
        TRIAL_ISSUED,
        SUBSCRIPTION_PAID,
        EXPIRY_REMINDER,
        SUBSCRIPTION_SUSPENDED,
        SUBSCRIPTION_EXTENDED,
        HOTSPOT_EXPIRY_NUDGE,
        HOTSPOT_DATA_NUDGE,
        FUP_NOTICE,
        DUNNING_RETRY,
        WINBACK_FIRST,
        WINBACK_SECOND,
        WINBACK_FINAL
    }

    @Id
    @Enumerated(EnumType.STRING)
    private Key templateKey;

    /**
     * Which language this wording is in.
     *
     * <p>Part of the key rather than a column, because a bilingual operator
     * needs both at once. Before this they had to choose, and half their
     * customers got messages they could not read.
     */
    @Id
    @Builder.Default
    @Column(nullable = false, length = 8)
    private String language = "en";

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
