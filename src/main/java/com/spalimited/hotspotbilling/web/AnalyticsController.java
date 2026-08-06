package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Business insights across the hotspot and PPPoE sides of the operation. */
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FINANCE')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.overview(days);
    }
}
