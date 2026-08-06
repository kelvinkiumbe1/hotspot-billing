package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MikrotikService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everyone online right now, across every router — the operational view an
 * ISP lives in. Includes a disconnect action (HTTP Basic, ADMIN role).
 */
@RestController
@RequestMapping("/api/admin/sessions")
@RequiredArgsConstructor
public class ActiveSessionController {

    private final RouterRepository routers;
    private final MikrotikService mikrotikService;
    private final AuditService audit;

    @GetMapping
    public Map<String, Object> all() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        int offlineRouters = 0;
        for (Router router : routers.findByEnabledTrue()) {
            List<Map<String, String>> found;
            try {
                found = mikrotikService.activeSessions(router);
            } catch (Exception e) {
                offlineRouters++;
                continue;
            }
            if (found.isEmpty() && !router.isOnline()) {
                offlineRouters++;
            }
            for (Map<String, String> s : found) {
                Map<String, Object> row = new LinkedHashMap<>(s);
                row.put("routerId", router.getId());
                row.put("routerName", router.getName());
                sessions.add(row);
            }
        }
        return Map.of(
                "sessions", sessions,
                "total", sessions.size(),
                "hotspot", sessions.stream().filter(s -> "hotspot".equals(s.get("kind"))).count(),
                "pppoe", sessions.stream().filter(s -> "pppoe".equals(s.get("kind"))).count(),
                "unreachableRouters", offlineRouters);
    }

    public record DisconnectRequest(@NotNull Long routerId, @NotBlank String user, @NotBlank String kind) {
    }

    /** Kicks a live session; the customer can log in again unless revoked. */
    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@Valid @RequestBody DisconnectRequest request, Principal principal) {
        Router router = routers.findById(request.routerId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown router: " + request.routerId()));
        mikrotikService.disconnectSession(router, request.user(), request.kind());
        audit.record(principal, "session.disconnect",
                "Disconnected " + request.user() + " (" + request.kind() + ") on " + router.getName());
        return Map.of("success", true, "message", request.user() + " disconnected");
    }
}
