package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.MikrotikSettings;
import com.spalimited.hotspotbilling.service.MikrotikService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Admin-editable integration settings (HTTP Basic, ADMIN role). */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class SettingsController {

    private final MikrotikService mikrotikService;
    private final com.spalimited.hotspotbilling.service.CustomPlanService customPlanService;

    public record MikrotikSettingsRequest(
            boolean enabled,
            @NotBlank String host,
            int port,
            String username,
            String password,
            boolean useSsl,
            String certificate,
            String hotspotServer,
            String interfaceName,
            String dnsName,
            Boolean macBinding) {

        MikrotikSettings toEntity() {
            return MikrotikSettings.builder()
                    .enabled(enabled)
                    .host(host)
                    .port(port)
                    .username(username)
                    .password(password)
                    .useSsl(useSsl)
                    .certificate(certificate)
                    .hotspotServer(hotspotServer)
                    .interfaceName(interfaceName)
                    .dnsName(dnsName)
                    .macBinding(macBinding != null && macBinding)
                    .build();
        }
    }

    @GetMapping("/mikrotik")
    public MikrotikSettings mikrotik() {
        return mikrotikService.settings();
    }

    @PutMapping("/mikrotik")
    public MikrotikSettings updateMikrotik(@Valid @RequestBody MikrotikSettingsRequest request) {
        return mikrotikService.updateSettings(request.toEntity());
    }

    /** Tries to connect and log in with the submitted (unsaved) settings. */
    @PostMapping("/mikrotik/test")
    public Map<String, Object> testMikrotik(@Valid @RequestBody MikrotikSettingsRequest request) {
        mikrotikService.testConnection(request.toEntity());
        return Map.of("success", true, "message", "Connected and logged in successfully");
    }

    // --- Pay-per-minute custom pass ---

    public record CustomPlanRequest(
            boolean enabled,
            @NotNull java.math.BigDecimal pricePerHour,
            String bandwidth,
            int minMinutes,
            int maxMinutes) {
    }

    @GetMapping("/custom-plan")
    public com.spalimited.hotspotbilling.domain.CustomPlanSettings customPlan() {
        return customPlanService.settings();
    }

    @PutMapping("/custom-plan")
    public com.spalimited.hotspotbilling.domain.CustomPlanSettings updateCustomPlan(@Valid @RequestBody CustomPlanRequest request) {
        return customPlanService.update(com.spalimited.hotspotbilling.domain.CustomPlanSettings.builder()
                .enabled(request.enabled())
                .pricePerHour(request.pricePerHour())
                .bandwidth(request.bandwidth())
                .minMinutes(request.minMinutes())
                .maxMinutes(request.maxMinutes())
                .build());
    }
}
