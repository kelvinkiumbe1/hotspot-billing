package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.RadiusClient;
import com.spalimited.hotspotbilling.domain.RadiusSession;
import com.spalimited.hotspotbilling.domain.RadiusSettings;
import com.spalimited.hotspotbilling.repository.RadiusClientRepository;
import com.spalimited.hotspotbilling.repository.RadiusSessionRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.radius.RadiusDisconnectService;
import com.spalimited.hotspotbilling.service.radius.RadiusServer;
import com.spalimited.hotspotbilling.service.radius.RadiusSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Managing the RADIUS side: which routers may ask, what is connected, and
 * cutting somebody off.
 */
@RestController
@RequestMapping("/api/admin/radius")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class RadiusController {

    private final RadiusClientRepository clients;
    private final RadiusSessionRepository sessions;
    private final RadiusSettingsService settingsService;
    private final RadiusServer server;
    private final RadiusDisconnectService disconnects;
    private final AuditService audit;

    @GetMapping
    public Map<String, Object> overview() {
        RadiusSettings settings = settingsService.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", settings.isEnabled());
        out.put("running", server.isRunning());
        out.put("authPort", settings.getAuthPort());
        out.put("acctPort", settings.getAcctPort());
        out.put("interimSeconds", settings.getInterimSeconds());
        out.put("disconnectEnabled", settings.isDisconnectEnabled());
        out.put("clients", clients.findAllByOrderByNameAsc().stream()
                .map(RadiusController::client).toList());
        out.put("liveSessions", sessions.countByStoppedAtIsNull());
        // Said explicitly because it is the thing an operator most needs to
        // know and least expects: the switch alone changes nothing until the
        // routers are pointed here.
        out.put("note", settings.isEnabled() && clients.findByEnabledTrue().isEmpty()
                ? "RADIUS is on but no router is allowed to ask it anything yet."
                : null);
        return out;
    }

    public record SettingsRequest(boolean enabled, Integer authPort, Integer acctPort,
                                  Integer interimSeconds, boolean disconnectEnabled) {
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody SettingsRequest request, Principal principal) {
        RadiusSettings incoming = RadiusSettings.builder()
                .enabled(request.enabled())
                .authPort(request.authPort() == null ? 1812 : request.authPort())
                .acctPort(request.acctPort() == null ? 1813 : request.acctPort())
                .interimSeconds(request.interimSeconds() == null ? 300 : request.interimSeconds())
                .disconnectEnabled(request.disconnectEnabled())
                .build();
        RadiusSettings saved = settingsService.save(incoming, principal.getName());
        audit.record(principal, "radius.settings",
                saved.isEnabled() ? "Turned RADIUS on" : "Turned RADIUS off");

        // Applied immediately rather than at the next restart: an operator who
        // turns it on and sees nothing happen concludes it does not work.
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            server.restart();
            out.put("running", server.isRunning());
            out.put("message", saved.isEnabled()
                    ? "RADIUS is listening on " + saved.getAuthPort() + " and " + saved.getAcctPort()
                    : "RADIUS is off.");
        } catch (RuntimeException e) {
            out.put("running", false);
            out.put("message", e.getMessage());
        }
        out.put("enabled", saved.isEnabled());
        return out;
    }

    public record ClientRequest(
            @NotBlank String name,
            @NotBlank String address,
            String sharedSecret,
            RadiusClient.Vendor vendor,
            Long routerId,
            Integer coaPort,
            Boolean enabled,
            String notes) {
    }

    @PostMapping("/clients")
    public Map<String, Object> createClient(@Valid @RequestBody ClientRequest request,
                                            Principal principal) {
        if (request.sharedSecret() == null || request.sharedSecret().isBlank()) {
            throw new IllegalArgumentException("A shared secret is required — it is the only thing "
                    + "standing between this server and anyone else on the network");
        }
        clients.findByAddress(request.address().trim()).ifPresent(existing -> {
            throw new IllegalArgumentException(request.address() + " is already configured");
        });
        RadiusClient client = RadiusClient.builder().address(request.address().trim()).build();
        apply(client, request);
        RadiusClient saved = clients.save(client);
        audit.record(principal, "radius.client.create",
                "Allowed " + saved.getAddress() + " (" + saved.getName() + ") to use RADIUS");
        return client(saved);
    }

    @PutMapping("/clients/{id}")
    public Map<String, Object> updateClient(@PathVariable Long id,
                                            @Valid @RequestBody ClientRequest request,
                                            Principal principal) {
        RadiusClient client = clients.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such RADIUS client"));
        client.setAddress(request.address().trim());
        apply(client, request);
        RadiusClient saved = clients.save(client);
        audit.record(principal, "radius.client.update", "Updated RADIUS client " + saved.getName());
        return client(saved);
    }

    @DeleteMapping("/clients/{id}")
    public Map<String, Object> deleteClient(@PathVariable Long id, Principal principal) {
        RadiusClient client = clients.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such RADIUS client"));
        clients.delete(client);
        audit.record(principal, "radius.client.delete",
                "Stopped " + client.getAddress() + " from using RADIUS");
        return Map.of("deleted", true);
    }

    @GetMapping("/sessions")
    public Map<String, Object> liveSessions() {
        List<RadiusSession> live = sessions.findByStoppedAtIsNullOrderByStartedAtDesc();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", live.stream().map(RadiusController::session).toList());
        out.put("recent", sessions.findTop100ByOrderByStartedAtDesc().stream()
                .filter(s -> s.getStoppedAt() != null)
                .map(RadiusController::session).toList());
        return out;
    }

    /**
     * Cuts a live session off now rather than when its clock runs out. Reports
     * honestly when the NAS did not answer, because "we cut them off" and "we
     * asked and heard nothing" are very different things to act on.
     */
    @PostMapping("/sessions/disconnect")
    public Map<String, Object> disconnect(@RequestBody Map<String, String> body, Principal principal) {
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Which login?");
        }
        RadiusDisconnectService.Result result = disconnects.disconnect(username);
        audit.record(principal, "radius.disconnect", "Asked to cut off " + username
                + " (" + result.acknowledged() + " of " + result.asked() + " sessions)");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asked", result.asked());
        out.put("acknowledged", result.acknowledged());
        out.put("problems", result.problems());
        out.put("message", result.asked() == 0
                ? "Nothing is connected under that name."
                : result.allAcknowledged()
                        ? "Cut off."
                        : result.acknowledged() + " of " + result.asked()
                                + " sessions ended; the rest did not answer.");
        return out;
    }

    private void apply(RadiusClient client, ClientRequest request) {
        client.setName(request.name().trim());
        client.setVendor(request.vendor() == null ? RadiusClient.Vendor.GENERIC : request.vendor());
        client.setRouterId(request.routerId());
        client.setCoaPort(request.coaPort() == null || request.coaPort() <= 0 ? 3799 : request.coaPort());
        client.setEnabled(request.enabled() == null || request.enabled());
        client.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
        // Blank means unchanged, so editing the vendor does not silently wipe
        // the secret and take every router at that address offline.
        if (request.sharedSecret() != null && !request.sharedSecret().isBlank()
                && !request.sharedSecret().startsWith("••••")) {
            client.setSharedSecret(request.sharedSecret().trim());
        }
    }

    private static Map<String, Object> client(RadiusClient c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("name", c.getName());
        row.put("address", c.getAddress());
        row.put("vendor", c.getVendor().name());
        row.put("routerId", c.getRouterId());
        row.put("enabled", c.isEnabled());
        row.put("coaPort", c.getCoaPort());
        row.put("lastRequestAt", c.getLastRequestAt());
        row.put("accepts", c.getAccepts());
        row.put("rejects", c.getRejects());
        row.put("notes", c.getNotes());
        // Never the secret itself — not even to an owner, and not even masked
        // enough to be worth guessing from.
        row.put("hasSecret", c.getSharedSecret() != null && !c.getSharedSecret().isBlank());
        return row;
    }

    private static Map<String, Object> session(RadiusSession s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("username", s.getUsername());
        row.put("kind", s.getKind().name());
        row.put("nasAddress", s.getNasAddress());
        row.put("framedIp", s.getFramedIp());
        row.put("callingStation", s.getCallingStation());
        row.put("startedAt", s.getStartedAt());
        row.put("lastUpdateAt", s.getLastUpdateAt());
        row.put("stoppedAt", s.getStoppedAt());
        row.put("terminateCause", s.getTerminateCause());
        row.put("sessionSeconds", s.getSessionSeconds());
        row.put("bytes", s.getInOctets() + s.getOutOctets());
        return row;
    }
}
