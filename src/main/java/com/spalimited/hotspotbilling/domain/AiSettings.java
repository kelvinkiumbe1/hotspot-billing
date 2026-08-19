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

    /**
     * Groq model id.
     *
     * <p>Free text on purpose — Groq's catalogue turns over faster than this
     * codebase does, and an operator who wants a cheaper or newer model should
     * not have to wait for a release. The cost of that is this exact bug:
     * llama-3.3-70b-versatile was the default until Groq decommissioned it, at
     * which point every question came back as "model does not exist" and the
     * assistant looked broken rather than out of date.
     *
     * <p>Ask Groq what it will serve rather than trusting this list:
     * {@code GET https://api.groq.com/openai/v1/models} with the operator's own
     * key answers definitively, because access is per-account.
     */
    @Builder.Default
    @Column(nullable = false, length = 80)
    private String model = DEFAULT_MODEL;

    /**
     * What a fresh install asks for, and what a decommissioned model is moved
     * to.
     *
     * <p>Chosen over the other chat models Groq currently serves because it is
     * the largest, and because it returns its reasoning in a separate field.
     * The Qwen model on the same catalogue emits its chain of thought inside
     * the answer, wrapped in {@code <think>} tags, which an operator would read
     * as the assistant talking to itself.
     */
    public static final String DEFAULT_MODEL = "openai/gpt-oss-120b";

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
