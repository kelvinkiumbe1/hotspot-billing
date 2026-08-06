package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.service.CustomPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.Comparator;
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
        LocalTime now = LocalTime.now();
        return planRepository.findAll().stream()
                // The pay-per-minute "Custom Time" row only exists to hang
                // custom payments and vouchers off — never a plan to buy.
                .filter(p -> !CustomPlanService.SYSTEM_PLAN_NAME.equals(p.getName()))
                .filter(Plan::isOnSale)
                // PPPoE packages are sold by the office, not the captive portal.
                .filter(p -> p.getEffectiveType() == Plan.Type.HOTSPOT)
                // A night plan should not be offered at two in the afternoon.
                .filter(p -> p.isUsableAt(now))
                .sorted(Comparator.comparing(Plan::getPrice))
                .toList();
    }
}
