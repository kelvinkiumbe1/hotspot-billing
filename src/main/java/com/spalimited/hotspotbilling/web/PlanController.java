package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.repository.PlanRepository;
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
        return planRepository.findByActiveTrueOrderByPriceAsc();
    }
}
