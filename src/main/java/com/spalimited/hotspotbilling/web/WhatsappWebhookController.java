package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.config.WhatsappSignatureGuard;
import com.spalimited.hotspotbilling.service.FieldBotService;
import com.spalimited.hotspotbilling.service.WhatsappBotService;
import com.spalimited.hotspotbilling.service.WhatsappService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * WhatsApp self-service: Meta's Cloud API posts inbound customer messages here,
 * the bot works out a reply, and we send it back. Also a small admin preview so
 * an operator can try the conversation without a real phone.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WhatsappWebhookController {

    private final WhatsappBotService bot;
    private final FieldBotService fieldBot;
    private final WhatsappService whatsapp;
    private final WhatsappSignatureGuard signatureGuard;
    private final ObjectMapper mapper;
    private final com.spalimited.hotspotbilling.repository.PaymentRepository payments;
    private final com.spalimited.hotspotbilling.service.MessagingSettingsService messagingSettings;

    /** The public HTTPS base is already proven by the M-Pesa callback. */
    @Value("${mpesa.callback-url:}")
    private String mpesaCallbackUrl;

    private String publicWebhookUrl() {
        if (mpesaCallbackUrl == null || !mpesaCallbackUrl.startsWith("http")) {
            return null;
        }
        int path = mpesaCallbackUrl.indexOf('/', mpesaCallbackUrl.indexOf("//") + 2);
        String base = path > 0 ? mpesaCallbackUrl.substring(0, path) : mpesaCallbackUrl;
        return base + "/api/whatsapp/webhook";
    }

    /** Meta's one-time webhook verification handshake. */
    @GetMapping("/api/whatsapp/webhook")
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String expected = messagingSettings.whatsappVerifyToken();
        if ("subscribe".equals(mode) && expected != null && !expected.isBlank()
                && expected.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        log.warn("WhatsApp webhook verification refused (mode={}, token matched={})",
                mode, expected != null && expected.equals(token));
        return ResponseEntity.status(403).body("Verification failed");
    }

    /**
     * Inbound messages. Always 200 quickly once accepted, so Meta doesn't
     * retry — but only after proving the delivery is Meta's.
     *
     * <p>Takes the raw bytes rather than a parsed body on purpose: the
     * signature covers exactly what was sent, so parsing and re-serialising
     * would change the bytes and no signature would ever match.
     */
    @PostMapping("/api/whatsapp/webhook")
    public ResponseEntity<String> inbound(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        signatureGuard.assertFromMeta(rawBody, signature);
        Map<String, Object> body;
        try {
            body = rawBody == null || rawBody.length == 0
                    ? Map.of() : mapper.readValue(rawBody, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("WhatsApp inbound was not readable JSON: {}", e.getMessage());
            return ResponseEntity.ok("EVENT_RECEIVED");
        }
        try {
            for (Map<String, Object> entry : asList(body.get("entry"))) {
                for (Map<String, Object> change : asList(entry.get("changes"))) {
                    Map<String, Object> value = asMap(change.get("value"));
                    for (Map<String, Object> msg : asList(value.get("messages"))) {
                        if (!"text".equals(str(msg.get("type")))) continue;
                        String from = str(msg.get("from"));
                        String text = str(asMap(msg.get("text")).get("body"));
                        if (from == null) continue;
                        whatsapp.send(from, answer(from, text));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("WhatsApp inbound parse error: {}", e.getMessage());
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    /**
     * One WhatsApp number serves both audiences. A message from a number on an
     * active technician's record is field work; everyone else is a customer.
     * Technicians are the smaller, known set, so they are checked first — and a
     * technician buying their own voucher can still do it from another phone.
     */
    private String answer(String from, String text) {
        String staffReply = fieldBot.reply(from, text);
        return staffReply != null ? staffReply : bot.replyWithPhone(from, text);
    }

    public record Sim(String phone, String text) {
    }

    /** Admin preview: run the bot and return its reply, sending nothing. */
    @PostMapping("/api/admin/whatsapp/simulate")
    public Map<String, String> simulate(@RequestBody Sim sim) {
        String phone = sim.phone() == null || sim.phone().isBlank() ? "254700000000" : sim.phone();
        return Map.of("reply", answer(phone, sim.text()));
    }

    /** What the operator needs to connect Meta's webhook to this account. */
    @GetMapping("/api/admin/whatsapp/config")
    public Map<String, Object> config() {
        String token = messagingSettings.whatsappVerifyToken();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("webhookPath", "/api/whatsapp/webhook");
        out.put("verifyToken", token == null ? "" : token);
        out.put("configured", token != null && !token.isBlank());
        // The address Meta must reach. Derived from the M-Pesa callback URL,
        // which is already a public HTTPS address that works — asking for the
        // same thing twice is how the two drift apart.
        out.put("publicWebhookUrl", publicWebhookUrl());
        out.put("inboundVerified", messagingSettings.settings().isInboundVerifiable());
        // The preview needs somebody to be. Defaulting it to an invented
        // number made every lookup answer "we can't find you" and the message
        // feed watch a phone that has never existed — so the last customer who
        // actually bought something is a far better starting point.
        out.put("suggestedPhone", lastCustomerPhone());
        return out;
    }

    private String lastCustomerPhone() {
        return payments.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(p -> p.getStatus() == com.spalimited.hotspotbilling.domain.Payment.Status.SUCCESS)
                .map(com.spalimited.hotspotbilling.domain.Payment::getPhoneNumber)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
