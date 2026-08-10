package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.AiSettings;
import com.spalimited.hotspotbilling.service.AiService;
import com.spalimited.hotspotbilling.service.AiSettingsService;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/** The owner's AI assistant (Groq). Owner-only. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class AiController {

    private final AiService ai;
    private final AiSettingsService settings;
    private final AuditService audit;

    @GetMapping("/settings/ai")
    public Map<String, Object> get() {
        return settings.describe();
    }

    public record AiRequest(boolean enabled, String apiKey, String model) {
    }

    @PutMapping("/settings/ai")
    public Map<String, Object> save(@RequestBody AiRequest req, Principal principal) {
        settings.save(AiSettings.builder()
                .enabled(req.enabled())
                .apiKey(req.apiKey())
                .model(req.model())
                .build());
        audit.record(principal, "settings.ai", "Updated AI assistant (" + (req.enabled() ? "on" : "off") + ")");
        return settings.describe();
    }

    public record AskRequest(@NotBlank String question) {
    }

    @PostMapping("/ai/ask")
    public Map<String, Object> ask(@RequestBody AskRequest req, Principal principal) {
        String answer = ai.ask(req.question());
        audit.record(principal, "ai.ask", "Asked the assistant a question");
        return Map.of("answer", answer);
    }
}
