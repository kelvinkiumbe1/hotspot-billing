package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.VpnSettings;
import com.spalimited.hotspotbilling.service.vpn.VpnService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** The management tunnel: settings, and each router's place on it. */
@RestController
@RequestMapping("/api/admin/vpn")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class VpnController {

    private final VpnService vpnService;

    @GetMapping
    public Map<String, Object> overview() {
        VpnSettings cfg = vpnService.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("serverPublicKey", cfg.getServerPublicKey());
        out.put("endpoint", cfg.getEndpoint());
        out.put("subnet", cfg.getSubnet());
        out.put("serverAddress", cfg.getServerAddress());
        out.put("keepaliveSeconds", cfg.getKeepaliveSeconds());
        out.put("interfaceName", cfg.getInterfaceName());
        out.put("usable", vpnService.whyNotUsable() == null);
        out.put("whyNotUsable", vpnService.whyNotUsable());
        out.put("routers", vpnService.overview());
        return out;
    }

    public record SettingsRequest(boolean enabled,
                                  @Size(max = 64) String serverPublicKey,
                                  @Size(max = 255) String endpoint,
                                  @Size(max = 32) String subnet,
                                  @Size(max = 64) String serverAddress,
                                  @Min(10) @Max(180) int keepaliveSeconds,
                                  @Size(max = 64) String interfaceName) {
    }

    @PutMapping("/settings")
    public Map<String, Object> save(@Valid @RequestBody SettingsRequest request,
                                    Principal principal) {
        vpnService.save(VpnSettings.builder()
                .enabled(request.enabled())
                .serverPublicKey(request.serverPublicKey())
                .endpoint(request.endpoint())
                .subnet(request.subnet())
                .serverAddress(request.serverAddress())
                .keepaliveSeconds(request.keepaliveSeconds())
                .interfaceName(request.interfaceName())
                .build(), principal == null ? "admin" : principal.getName());
        return overview();
    }

    /** Configures the tunnel on one router, over the API. */
    @PostMapping("/{routerId}/configure")
    public Map<String, Object> configure(@PathVariable Long routerId, Principal principal) {
        VpnService.Configured result = vpnService.configure(
                routerId, principal == null ? "admin" : principal.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("address", result.address());
        out.put("publicKey", result.publicKey());
        out.put("peerStanza", result.peerStanza());
        out.put("message", result.message());
        return out;
    }

    /**
     * The same commands as text.
     *
     * <p>For the router that is already unreachable -- the one that needs the
     * tunnel most and cannot be configured over the API precisely because it has
     * no way in yet.
     */
    @GetMapping("/{routerId}/script")
    public Map<String, Object> script(@PathVariable Long routerId) {
        return Map.of("commands", vpnService.script(routerId));
    }

    /** Tries the tunnel address specifically, and records the result. */
    @PostMapping("/{routerId}/check")
    public Map<String, Object> check(@PathVariable Long routerId) {
        return vpnService.check(routerId);
    }

    /** Every peer block at once, for building the server config in one go. */
    @GetMapping("/peers")
    public Map<String, Object> peers() {
        return Map.of("config", vpnService.allPeerStanzas());
    }

    /** The next address that would be handed out, so the screen can show it. */
    @GetMapping("/next-address")
    public Map<String, Object> nextAddress() {
        try {
            return Map.of("address", vpnService.nextFreeAddress());
        } catch (RuntimeException e) {
            return Map.of("error", e.getMessage());
        }
    }
}
