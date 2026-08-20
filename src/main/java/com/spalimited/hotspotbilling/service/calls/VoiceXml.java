package com.spalimited.hotspotbilling.service.calls;

import java.util.List;

/**
 * The XML a voice provider expects back when it asks what to do with a call.
 *
 * <p>Africa's Talking Voice works the way TR-069 does: the provider calls us, and
 * the reply to that one HTTP request is the entire instruction for what happens
 * next. There is no second chance to correct it and no error surface -- a
 * malformed document is a caller hearing silence and hanging up, with nothing in
 * our logs to say why.
 *
 * <p>Which is the whole reason this is a separate class with no dependencies.
 * Placing a real call needs an account, a rented number and real money; building
 * the document does not, so every branch of it can be tested exactly.
 *
 * <p>Escaping is not a detail here. A greeting reading "Karibu &amp; welcome"
 * would, unescaped, produce a document the provider rejects, and the operator who
 * typed the ampersand would have no way to connect the two.
 */
public final class VoiceXml {

    private VoiceXml() {
    }

    /**
     * Ring these people, in this order.
     *
     * @param numbers      who to ring, in the order to ring them
     * @param greeting     played before ringing, or null to ring straight away
     * @param record       whether the provider should record the conversation
     * @param ringSeconds  how long to ring each number before moving on
     */
    public static String dial(List<String> numbers, String greeting, boolean record,
                              int ringSeconds) {
        if (numbers == null || numbers.isEmpty()) {
            throw new IllegalArgumentException("Nobody to dial");
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>\n");
        if (greeting != null && !greeting.isBlank()) {
            xml.append("  <Say>").append(escape(greeting)).append("</Say>\n");
        }
        xml.append("  <Dial phoneNumbers=\"")
                .append(escape(String.join(",", numbers)))
                // Sequentially, not all at once. Ringing every agent's phone for
                // one caller means three people stop what they are doing and two
                // of them find a dead line, which trains them to ignore it.
                .append("\" sequential=\"true\"")
                .append(" ringbackTone=\"\"")
                .append(" maxDuration=\"").append(Math.max(30, ringSeconds * 4)).append("\"");
        if (record) {
            xml.append(" record=\"true\"");
        }
        xml.append("/>\n</Response>\n");
        return xml.toString();
    }

    /** Say something and hang up. Used when nobody is available. */
    public static String sayAndHangUp(String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>\n"
                + "  <Say>" + escape(message == null || message.isBlank()
                        ? "Sorry, nobody is available to take your call right now."
                        : message) + "</Say>\n"
                + "</Response>\n";
    }

    /**
     * Refuse the call outright.
     *
     * <p>Distinct from saying something and hanging up: a rejected call usually
     * costs nothing and shows on the caller's phone as a failed call rather than
     * a connected one. Right for a call arriving while the line is switched off,
     * wrong for a customer nobody could answer -- they should hear a sentence.
     */
    public static String reject() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>\n  <Reject/>\n</Response>\n";
    }

    /**
     * The five characters that must never appear raw.
     *
     * <p>Quotes included, because these strings end up inside attributes as well
     * as element bodies and one escaper for both is one fewer thing to get wrong.
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                // Ampersand first would be wrong if it came later: escaping it
                // after the others would re-escape the ampersands they produce.
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
