package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** How the support phone line behaves. Single row (id = 1). See V75. */
@Entity
@Table(name = "call_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** The number customers ring. Rented from the voice provider. */
    @Column(name = "virtual_number", length = 32)
    private String virtualNumber;

    /**
     * voice.africastalking.com, not the api. host the SMS integration uses.
     * Overridable so an operator can point at the sandbox without a rebuild.
     */
    @Builder.Default
    @Column(name = "voice_base_url", nullable = false)
    private String voiceBaseUrl = "https://voice.africastalking.com";

    @Column(length = 500)
    private String greeting;

    @Column(name = "no_answer_message", length = 500)
    private String noAnswerMessage;

    /**
     * Off by default on purpose. A recording is personal data with a retention
     * obligation attached; switching it on should be a decision rather than an
     * inherited default.
     */
    @Builder.Default
    @Column(name = "record_calls", nullable = false)
    private boolean recordCalls = false;

    @Builder.Default
    @Column(name = "ring_seconds", nullable = false)
    private int ringSeconds = 25;

    /**
     * The secret in the webhook URL. See V75: the provider posts to us, so there
     * is no session to check and the URL is the only thing we control.
     */
    @Column(name = "callback_token", length = 64)
    private String callbackToken;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
