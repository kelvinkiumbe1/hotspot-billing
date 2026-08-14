package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Outages: a public status page anybody can check before they ring, and the
 * operator's own view of what is open.
 */
@RestController
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidents;

    /**
     * Public and deliberately free of customer detail — areas and times only.
     * A customer who can see for themselves that the fault is known and being
     * worked on is a support call that never happens.
     */
    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return incidents.publicStatus();
    }

    @GetMapping("/api/admin/incidents")
    @PreAuthorize("hasAuthority('NETWORK')")
    public Map<String, Object> overview() {
        return incidents.overview();
    }
}
