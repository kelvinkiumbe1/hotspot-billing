package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.WhatsappBotService;
import com.spalimited.hotspotbilling.service.WhatsappService;
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
    private final WhatsappService whatsapp;

    @Value("${whatsapp.webhook-verify-token:}")
    private String verifyToken;

    /** Meta's one-time webhook verification handshake. */
    @GetMapping("/api/whatsapp/webhook")
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && verifyToken != null && !verifyToken.isBlank()
                && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("Verification failed");
    }

    /** Inbound messages from customers. Always 200 quickly so Meta doesn't retry. */
    @PostMapping("/api/whatsapp/webhook")
    public ResponseEntity<String> inbound(@RequestBody Map<String, Object> body) {
        try {
            for (Map<String, Object> entry : asList(body.get("entry"))) {
                for (Map<String, Object> change : asList(entry.get("changes"))) {
                    Map<String, Object> value = asMap(change.get("value"));
                    for (Map<String, Object> msg : asList(value.get("messages"))) {
                        if (!"text".equals(str(msg.get("type")))) continue;
                        String from = str(msg.get("from"));
                        String text = str(asMap(msg.get("text")).get("body"));
                        if (from == null) continue;
                        String reply = bot.replyWithPhone(from, text);
                        whatsapp.send(from, reply);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("WhatsApp inbound parse error: {}", e.getMessage());
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    public record Sim(String phone, String text) {
    }

    /** Admin preview: run the bot and return its reply, sending nothing. */
    @PostMapping("/api/admin/whatsapp/simulate")
    public Map<String, String> simulate(@RequestBody Sim sim) {
        String phone = sim.phone() == null || sim.phone().isBlank() ? "254700000000" : sim.phone();
        return Map.of("reply", bot.replyWithPhone(phone, sim.text()));
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
