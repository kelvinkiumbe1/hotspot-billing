package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CallAgent;
import com.spalimited.hotspotbilling.domain.CallRecord;
import com.spalimited.hotspotbilling.domain.CallSettings;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.calls.CallCentreService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The support phone line: settings, who is on the rota, and every call. */
@RestController
@RequestMapping("/api/admin/calls")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CUSTOMERS')")
public class CallCentreController {

    private final CallCentreService callCentre;
    private final SubscriberRepository subscribers;

    // --- Settings ---

    /**
     * Everything the settings screen needs, including the webhook URL to paste
     * into the provider dashboard.
     *
     * <p>The URL is assembled from the request rather than configured, because
     * the operator is looking at the running server and asking it what its own
     * address is. A hostname typed into a settings field is a hostname that goes
     * stale the first time anything moves.
     */
    @GetMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest request) {
        CallSettings cfg = callCentre.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("virtualNumber", cfg.getVirtualNumber());
        out.put("voiceBaseUrl", cfg.getVoiceBaseUrl());
        out.put("greeting", cfg.getGreeting());
        out.put("noAnswerMessage", cfg.getNoAnswerMessage());
        out.put("recordCalls", cfg.isRecordCalls());
        out.put("ringSeconds", cfg.getRingSeconds());
        out.put("callbackUrl", publicBase(request) + "/api/voice/" + callCentre.callbackToken());
        out.put("usable", callCentre.usable());
        out.put("whyNotUsable", callCentre.whyNotUsable());
        return out;
    }

    public record SettingsRequest(boolean enabled, @Size(max = 32) String virtualNumber,
                                  @Size(max = 255) String voiceBaseUrl,
                                  @Size(max = 500) String greeting,
                                  @Size(max = 500) String noAnswerMessage,
                                  boolean recordCalls,
                                  @Min(10) @Max(120) int ringSeconds) {
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@Valid @RequestBody SettingsRequest request,
                                           HttpServletRequest http, Principal principal) {
        callCentre.save(CallSettings.builder()
                .enabled(request.enabled())
                .virtualNumber(request.virtualNumber())
                .voiceBaseUrl(request.voiceBaseUrl())
                .greeting(request.greeting())
                .noAnswerMessage(request.noAnswerMessage())
                .recordCalls(request.recordCalls())
                .ringSeconds(request.ringSeconds())
                .build(), who(principal));
        return settings(http);
    }

    @PostMapping("/settings/rotate-token")
    public Map<String, Object> rotateToken(HttpServletRequest request) {
        callCentre.rotateCallbackToken();
        Map<String, Object> out = new LinkedHashMap<>(settings(request));
        out.put("message", "New webhook address. Paste it into your provider dashboard now — "
                + "until you do, incoming calls will not reach anybody.");
        return out;
    }

    // --- Agents ---

    @GetMapping("/agents")
    public Map<String, Object> agents() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CallAgent agent : callCentre.allAgents()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", agent.getId());
            row.put("name", agent.getName());
            row.put("phoneNumber", agent.getPhoneNumber());
            row.put("priority", agent.getPriority());
            row.put("active", agent.isActive());
            // Both, because they mean different things: off the rota, versus on
            // it and currently talking.
            row.put("available", agent.isAvailable());
            row.put("busyUntil", agent.getBusyUntil());
            rows.add(row);
        }
        return Map.of("agents", rows);
    }

    public record AgentRequest(Long id, @NotBlank @Size(max = 120) String name,
                               @NotBlank @Size(max = 32) String phoneNumber,
                               @Min(1) @Max(999) int priority, boolean active) {
    }

    @PostMapping("/agents")
    public Map<String, Object> saveAgent(@Valid @RequestBody AgentRequest request) {
        CallAgent saved = callCentre.saveAgent(CallAgent.builder()
                .id(request.id())
                .name(request.name())
                .phoneNumber(request.phoneNumber())
                .priority(request.priority())
                .active(request.active())
                .build());
        return Map.of("id", saved.getId(), "message", saved.getName() + " saved.");
    }

    @DeleteMapping("/agents/{id}")
    public Map<String, Object> deleteAgent(@PathVariable Long id) {
        callCentre.deleteAgent(id);
        return Map.of("ok", true);
    }

    @PostMapping("/agents/{id}/free")
    public Map<String, Object> freeAgent(@PathVariable Long id) {
        CallAgent agent = callCentre.clearBusy(id);
        return Map.of("ok", true, "message", agent.getName() + " is back on the rota.");
    }

    // --- Calling ---

    public record DialRequest(Long agentId, Long subscriberId,
                              @Size(max = 32) String phoneNumber, Long ticketId) {
    }

    /**
     * Rings an agent so they can be bridged to a customer.
     *
     * <p>The number comes from the customer record where there is one, so a stale
     * screen cannot dial a number the customer has since changed.
     */
    @PostMapping("/dial")
    public Map<String, Object> dial(@Valid @RequestBody DialRequest request) {
        String number = request.phoneNumber();
        if (request.subscriberId() != null) {
            Subscriber sub = subscribers.findById(request.subscriberId())
                    .orElseThrow(() -> new IllegalArgumentException("No such customer"));
            number = sub.getPhoneNumber();
        }
        CallCentreService.Dialled result = callCentre.dial(request.agentId(), number,
                request.subscriberId(), request.ticketId());
        return Map.of("ok", result.ok(),
                "sessionId", result.sessionId() == null ? "" : result.sessionId(),
                "message", result.message());
    }

    // --- Reading ---

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(callCentre.stats());
        out.put("calls", render(callCentre.recent()));
        out.put("liveCalls", render(callCentre.live()));
        out.put("usable", callCentre.usable());
        out.put("whyNotUsable", callCentre.whyNotUsable());
        return out;
    }

    /**
     * Live calls only, for the screen pop.
     *
     * <p>Polled every few seconds by whoever has the admin open, which is why it
     * is deliberately the cheapest endpoint here: two indexed reads and no joins
     * beyond naming the customer.
     */
    @GetMapping("/live")
    public Map<String, Object> live() {
        return Map.of("calls", render(callCentre.live()));
    }

    @GetMapping("/subscriber/{id}")
    public Map<String, Object> forSubscriber(@PathVariable Long id) {
        return Map.of("calls", render(callCentre.forSubscriber(id)));
    }

    public record NotesRequest(@Size(max = 2000) String notes) {
    }

    @PostMapping("/{id}/notes")
    public Map<String, Object> annotate(@PathVariable Long id, @Valid @RequestBody NotesRequest request) {
        callCentre.annotate(id, request.notes());
        return Map.of("ok", true, "message", "Saved.");
    }

    // --- Rendering ---

    private List<Map<String, Object>> render(List<CallRecord> records) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CallRecord c : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("direction", c.getDirection());
            row.put("status", c.getStatus());
            row.put("callerNumber", c.getCallerNumber());
            row.put("destinationNumber", c.getDestinationNumber());
            row.put("startedAt", c.getStartedAt());
            row.put("answeredAt", c.getAnsweredAt());
            row.put("endedAt", c.getEndedAt());
            row.put("durationSeconds", c.getDurationSeconds());
            row.put("hangupCause", c.getHangupCause());
            row.put("recordingUrl", c.getRecordingUrl());
            row.put("cost", c.getCost());
            row.put("currency", c.getCurrency());
            row.put("notes", c.getNotes());
            row.put("ticketId", c.getTicketId());
            if (c.getSubscriberId() != null) {
                row.put("subscriberId", c.getSubscriberId());
                subscribers.findById(c.getSubscriberId()).ifPresent(sub -> {
                    row.put("customer", sub.getFullName());
                    row.put("pppoeUsername", sub.getPppoeUsername());
                    // What somebody answering the phone needs in the first two
                    // seconds: are they paid up, and are they cut off. Named
                    // accountStatus so it cannot be confused with the call's own
                    // status, which is already on this row.
                    row.put("accountStatus", sub.getStatus());
                    row.put("paidUntil", sub.getPaidUntil());
                });
            }
            out.add(row);
        }
        return out;
    }

    /**
     * The address the outside world reaches this server on.
     *
     * <p>Behind the reverse proxy the scheme and host arrive in X-Forwarded-*,
     * and without them the URL shown would be http://localhost:8081 -- which
     * looks plausible, gets pasted into a provider dashboard, and produces a
     * phone line that silently never rings.
     */
    private static String publicBase(HttpServletRequest request) {
        String proto = header(request, "X-Forwarded-Proto");
        String host = header(request, "X-Forwarded-Host");
        if (host != null) {
            return (proto != null ? proto : "https") + "://" + host;
        }
        String url = request.getRequestURL().toString();
        int at = url.indexOf("/api/");
        return at > 0 ? url.substring(0, at) : url;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        // A proxy chain sends a comma-separated list; the first hop is the one
        // the client actually asked for.
        return value.split(",")[0].strip();
    }

    private static String who(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
