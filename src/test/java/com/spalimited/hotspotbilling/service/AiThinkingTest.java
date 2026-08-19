package com.spalimited.hotspotbilling.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeping a model's train of thought out of the operator's chat window.
 *
 * <p>The model is a free-text setting, deliberately: Groq's catalogue turns over
 * faster than this app ships, and the previous default was retired out from
 * under it. The cost of that freedom is that an operator can pick a model which
 * reasons out loud. Groq's gpt-oss models return their reasoning in a field of
 * its own, but the Qwen model on the same catalogue puts it inside the answer,
 * wrapped in {@code <think>} tags — which reads as the assistant muttering to
 * itself before getting to the point.
 */
class AiThinkingTest {

    @Test
    @DisplayName("Thinking is removed and the answer is kept")
    void thinkingIsStripped() {
        String raw = """
                <think>
                The user is asking about today's sales. The data says KES 4200.
                I should also mention the offline router.
                </think>
                Today's sales came to KES 4,200. One of your three routers is offline.""";

        assertThat(AiService.withoutThinking(raw))
                .isEqualTo("Today's sales came to KES 4,200. One of your three routers is offline.");
    }

    @Test
    @DisplayName("An answer with no thinking in it is left alone")
    void ordinaryAnswersAreUntouched() {
        // The models this ships with do not do this at all, so the common path
        // has to be a no-op rather than a reformat.
        String plain = "Today's sales came to KES 4,200.";

        assertThat(AiService.withoutThinking(plain)).isEqualTo(plain);
    }

    @Test
    @DisplayName("Thinking that never closed takes everything after it")
    void unclosedThinkingIsDropped() {
        // A reply cut off by the token limit mid-thought has no answer in it.
        // Showing the thinking because the tag never closed would be worse than
        // showing nothing: the operator would read a half-formed guess as advice.
        String truncated = "<think>The user wants sales. Let me check whether the figure includes";

        assertThat(AiService.withoutThinking(truncated)).isEmpty();
    }

    @Test
    @DisplayName("More than one thought is removed, not just the first")
    void everyBlockIsRemoved() {
        String raw = "<think>first</think>Sales were KES 4,200. <think>second</think>"
                + "One router is offline.";

        assertThat(AiService.withoutThinking(raw))
                .isEqualTo("Sales were KES 4,200. One router is offline.");
    }

    @Test
    @DisplayName("Nothing at all is an empty answer rather than a crash")
    void nullIsHandled() {
        // The content field is absent rather than empty on some error shapes,
        // and this runs on the path that turns a reply into what the operator
        // reads.
        assertThat(AiService.withoutThinking(null)).isEmpty();
        assertThat(AiService.withoutThinking("   ")).isEmpty();
    }
}
