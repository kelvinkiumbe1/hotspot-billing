package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MikrotikService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Multi-router management and live status (HTTP Basic, ADMIN role). */
@RestController
@RequestMapping("/api/admin/routers")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class RouterController {

    private final RouterRepository routers;
    private final MikrotikService mikrotikService;
    private final AuditService audit;

    @GetMapping
    public List<Router> all() {
        mikrotikService.defaultRouter(); // seeds the first router on a fresh install
        return routers.findAllByOrderByNameAsc();
    }

    public record RouterRequest(
            @NotBlank String name,
            String location,
            @NotBlank String host,
            int port,
            String username,
            String password,
            boolean useSsl,
            boolean enabled,
            boolean defaultRouter) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Router create(@Valid @RequestBody RouterRequest request, Principal principal) {
        routers.findByName(request.name()).ifPresent(existing -> {
            throw new IllegalArgumentException("A router named '" + request.name() + "' already exists");
        });
        if (request.defaultRouter()) {
            routers.findFirstByDefaultRouterTrue().ifPresent(r -> {
                r.setDefaultRouter(false);
                routers.save(r);
            });
        }
        Router saved = routers.save(Router.builder()
                .name(request.name())
                .location(request.location())
                .host(request.host())
                .port(request.port() > 0 ? request.port() : 8728)
                .username(request.username())
                .password(request.password())
                .useSsl(request.useSsl())
                .enabled(request.enabled())
                .defaultRouter(request.defaultRouter())
                .build());
        audit.record(principal, "router.create", "Added router " + saved.getName() + " (" + saved.getHost() + ")");
        return saved;
    }

    @PutMapping("/{id}")
    @Transactional
    public Router update(@PathVariable Long id, @Valid @RequestBody RouterRequest request, Principal principal) {
        Router router = get(id);
        if (request.defaultRouter() && !router.isDefaultRouter()) {
            routers.findFirstByDefaultRouterTrue().ifPresent(r -> {
                r.setDefaultRouter(false);
                routers.save(r);
            });
        }
        router.setName(request.name());
        router.setLocation(request.location());
        router.setHost(request.host());
        router.setPort(request.port() > 0 ? request.port() : 8728);
        router.setUsername(request.username());
        if (request.password() != null && !request.password().isBlank()) {
            router.setPassword(request.password());
        }
        router.setUseSsl(request.useSsl());
        router.setEnabled(request.enabled());
        router.setDefaultRouter(request.defaultRouter());
        audit.record(principal, "router.update", "Updated router " + router.getName());
        return routers.save(router);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Principal principal) {
        Router router = get(id);
        audit.record(principal, "router.delete", "Removed router " + router.getName());
        routers.delete(router);
    }

    /** Connects right now and reports what the router says. */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        Router router = get(id);
        mikrotikService.testConnection(router);
        return Map.of("success", true, "message", "Connected to " + router.getName() + " successfully");
    }

    /** Fresh probe: uptime, version, session counts. */
    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable Long id) {
        return mikrotikService.probe(get(id));
    }

    /** Live hotspot + PPPoE sessions on this router. */
    @GetMapping("/{id}/sessions")
    public List<Map<String, String>> sessions(@PathVariable Long id) {
        return mikrotikService.activeSessions(get(id));
    }

    private Router get(Long id) {
        return routers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown router: " + id));
    }
}
