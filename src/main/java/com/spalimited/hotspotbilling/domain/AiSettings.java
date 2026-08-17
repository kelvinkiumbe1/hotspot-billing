package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The owner's AI assistant, powered by Groq (an OpenAI-compatible API).
 * Single row (id = 1). The API key is the operator's own — questions and a
 * snapshot of the business's own numbers are sent to Groq under it, which
 * the settings screen states plainly.
 */
@Entity
@Table(name = "ai_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** Groq API key (write-only; masked when read back). */
    @Column(length = 200)
    private String apiKey;

    /** Groq model id, e.g. llama-3.3-70b-versatile. */
    @Builder.Default
    @Column(nullable = false, length = 80)
    private String model = "llama-3.3-70b-versatile";

    /**
     * Draft a first reply for every support ticket that comes in, ready for a
     * human to send, edit or throw away. Nothing is ever sent automatically —
     * the customer only ever hears from a person who pressed send.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean draftTicketReplies = false;

    @jakarta.persistence.Transient
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
