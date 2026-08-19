package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.AiSettings;
import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's AI assistant. Sends the operator's question, plus a compact
 * snapshot of their own live numbers, to Groq's OpenAI-compatible chat API
 * under the operator's own key, and returns the reply. Read-only: it looks
 * at data and answers, it never changes anything.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    private final AiSettingsService settings;
    private final ObjectMapper mapper;
    private final PaymentRepository payments;
    private final SubscriptionPaymentRepository subscriptionPayments;
    private final VoucherRepository vouchers;
    private final SubscriberRepository subscribers;
    private final RouterRepository routers;
    private final PortalSettingsService portalSettings;
    private final SystemContextService systemContext;
    private final HttpClient http = HttpClient.newHttpClient();

    public boolean isEnabled() {
        return settings.get().isConfigured();
    }

    @Transactional(readOnly = true)
    public String ask(String question) {
        AiSettings s = settings.get();
        if (!s.isConfigured()) {
            throw new IllegalStateException("The assistant is off — turn it on and add your Groq API key first");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Ask a question first");
        }
        if (question.length() > 2000) {
            throw new IllegalArgumentException("That question is too long");
        }

        String system = "You are the assistant for a hotspot/ISP billing system called Zidi, "
                + "used by the operator \"" + portalSettings.settings().getBusinessName() + "\". "
                + "Answer the operator's questions clearly and concisely.\n"
                + "Everything below is about THIS operator's own system. Use it. If the answer is not "
                + "there, say so plainly — never guess at how the system behaves or at a figure. "
                + "When something is switched off, say it is off rather than describing what it would "
                + "do. When you name a problem, say where in the admin it is dealt with. Amounts are "
                + "in Kenyan Shillings (KES).\n"
                + "You can only read. You cannot change a setting, message a customer or move money; "
                + "if the operator asks you to, tell them where to do it themselves.\n"
                + "Answer as somebody who knows this system, not as somebody reading a report about "
                + "it. Never mention these notes, their section headings, or that you were given "
                + "them.\n"
                + "Reply in plain sentences. No markdown tables, headings, bullet lists or bold: "
                + "this is shown in a small chat bubble that renders none of it, so a table arrives "
                + "as a wall of pipe characters. Left to themselves these models answer everything "
                + "with a table, and the same habit ran the reply past its length limit and cut it "
                + "off mid-sentence.\n\n"
                + systemContext.forAssistant()
                + "\nCURRENT DATA:\n" + snapshot();

        // 1000 rather than 700. With the no-markdown instruction a full answer
        // runs to about 200 tokens, so this is headroom rather than a budget --
        // and running out mid-sentence is the one failure here that looks like
        // the assistant broke rather than like it was brief.
        return chat(system, question, 1000, 0.3);
    }

    /**
     * One turn against the operator's own model, with the caller's system
     * prompt. Shared so anything that wants the model — the assistant, the
     * ticket-reply copilot — goes through the same key, the same error
     * handling and the same "say it plainly when it fails" behaviour, rather
     * than each growing its own HTTP client.
     */
    public String chat(String system, String user, int maxTokens, double temperature) {
        AiSettings s = settings.get();
        if (!s.isConfigured()) {
            throw new IllegalStateException("The assistant is off — turn it on and add your Groq API key first");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", s.getModel());
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + s.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(40))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (response.statusCode() >= 300) {
                String msg = root.path("error").path("message").asString("");
                String code = root.path("error").path("code").asString("");
                // Groq retires models faster than this codebase changes, and its
                // own message says only that the model does not exist. Adding
                // where to change it turns a dead end into a ten-second fix.
                String hint = "model_not_found".equals(code) || "model_decommissioned".equals(code)
                        ? " Groq no longer serves that model — pick another under "
                          + "Settings \u2192 Assistant. \"" + AiSettings.DEFAULT_MODEL
                          + "\" works today."
                        : "";
                throw new IllegalStateException("The assistant could not answer"
                        + (msg.isBlank() ? " (HTTP " + response.statusCode() + ")" : ": " + msg)
                        + hint);
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("The assistant returned an empty reply");
            }
            return withoutThinking(choices.get(0).path("message").path("content").asString(""));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI request failed: {}", e.getMessage());
            throw new IllegalStateException("Could not reach the assistant right now. Try again shortly.");
        }
    }

    /**
     * The answer, with any chain of thought taken out of it.
     *
     * <p>Groq's gpt-oss models return their reasoning in a field of its own, so
     * this does nothing for them. It exists because the model is free text: an
     * operator who types a Qwen model gets its whole train of thought wrapped in
     * {@code <think>} tags at the top of the answer, which reads as the
     * assistant having a conversation with itself.
     *
     * <p>An unclosed tag drops everything after it rather than showing it. A
     * truncated answer whose thinking never finished has no answer in it, and
     * showing the thinking instead would be worse than showing nothing.
     */
    static String withoutThinking(String answer) {
        if (answer == null) {
            return "";
        }
        String out = answer;
        int open;
        while ((open = out.indexOf("<think>")) >= 0) {
            int close = out.indexOf("</think>", open);
            out = close < 0 ? out.substring(0, open)
                    : out.substring(0, open) + out.substring(close + "</think>".length());
        }
        return out.trim();
    }

    /** A short, plain-text digest of the operator's live numbers for grounding. */
    private String snapshot() {
        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();

        BigDecimal hotspotToday = payments.sumAmountByStatusSince(Payment.Status.SUCCESS, startOfDay);
        long hotspotCount = payments.countByStatusAndCompletedAtAfter(Payment.Status.SUCCESS, startOfDay);
        BigDecimal subsToday = subscriptionPayments.sumAmountByStatusSince(SubscriptionPayment.Status.SUCCESS, startOfDay);
        long subsCount = subscriptionPayments.countByStatusAndCompletedAtAfter(SubscriptionPayment.Status.SUCCESS, startOfDay);

        long activeVouchers = vouchers.countByStatus(Voucher.Status.ACTIVE);
        long unusedVouchers = vouchers.countByStatus(Voucher.Status.UNUSED);
        long totalSubs = subscribers.count();
        long activeSubs = subscribers.findByStatus(Subscriber.Status.ACTIVE).size();

        List<Router> enabled = routers.findByEnabledTrue();
        long onlineRouters = enabled.stream().filter(Router::isOnline).count();

        StringBuilder sb = new StringBuilder();
        sb.append("- Today's sales: KES ").append(hotspotToday.add(subsToday).toPlainString())
                .append(" total (hotspot ").append(hotspotCount).append(" sale(s) = KES ")
                .append(hotspotToday.toPlainString()).append(", subscriptions ").append(subsCount)
                .append(" payment(s) = KES ").append(subsToday.toPlainString()).append(")\n");
        sb.append("- Vouchers: ").append(activeVouchers).append(" active, ")
                .append(unusedVouchers).append(" unused/unsold\n");
        sb.append("- Home subscribers: ").append(totalSubs).append(" total, ")
                .append(activeSubs).append(" active\n");
        sb.append("- Routers: ").append(onlineRouters).append(" of ").append(enabled.size())
                .append(" online\n");
        return sb.toString();
    }
}
