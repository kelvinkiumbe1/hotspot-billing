package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.service.CustomPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public plan listing for the captive portal. Plan management lives in
 * AdminController.
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanRepository planRepository;

    @GetMapping
    public List<Plan> activePlans() {
        // The pay-per-minute "Custom Time" row only exists to hang custom
        // payments and vouchers off — it is never a plan a customer buys.
        return planRepository.findByActiveTrueOrderByPriceAsc().stream()
                .filter(p -> !CustomPlanService.SYSTEM_PLAN_NAME.equals(p.getName()))
                .toList();
    }
}
