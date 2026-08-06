package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CustomPlanSettings;
import com.spalimited.hotspotbilling.service.CustomPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public pricing info the portal uses to offer pay-per-minute passes. */
@RestController
@RequiredArgsConstructor
public class CustomPlanController {

    private final CustomPlanService customPlanService;

    @GetMapping("/api/custom-plan")
    public Map<String, Object> customPlan() {
        CustomPlanSettings s = customPlanService.settings();
        return Map.of(
                "enabled", s.isEnabled(),
                "pricePerHour", s.getPricePerHour(),
                "bandwidth", s.getBandwidth() != null ? s.getBandwidth() : "",
                "minMinutes", s.getMinMinutes(),
                "maxMinutes", s.getMaxMinutes());
    }
}
