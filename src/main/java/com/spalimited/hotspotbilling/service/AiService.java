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
                + "Answer the operator's questions clearly and concisely. Use the live figures below "
                + "when they are relevant. If the answer is not in the data, say so plainly rather than "
                + "guessing. Amounts are in Kenyan Shillings (KES). You cannot change anything — you only "
                + "read and explain.\n\nCURRENT DATA:\n" + snapshot();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", s.getModel());
        body.put("temperature", 0.3);
        body.put("max_tokens", 700);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", question)));

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
                throw new IllegalStateException("The assistant could not answer"
                        + (msg.isBlank() ? " (HTTP " + response.statusCode() + ")" : ": " + msg));
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("The assistant returned an empty reply");
            }
            return choices.get(0).path("message").path("content").asString("").trim();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI request failed: {}", e.getMessage());
            throw new IllegalStateException("Could not reach the assistant right now. Try again shortly.");
        }
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
