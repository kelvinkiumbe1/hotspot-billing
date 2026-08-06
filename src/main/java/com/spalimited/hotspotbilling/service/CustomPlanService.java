package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CustomPlanSettings;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.repository.CustomPlanSettingsRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pay-per-minute custom passes: pricing rules plus the hidden system plan
 * that carries the payment/voucher foreign keys. The system plan stays
 * inactive so it never shows in the normal plan list.
 */
@Service
@RequiredArgsConstructor
public class CustomPlanService {

    public static final String SYSTEM_PLAN_NAME = "Custom Time";

    private final CustomPlanSettingsRepository settingsRepository;
    private final PlanRepository planRepository;

    @Transactional
    public CustomPlanSettings settings() {
        return settingsRepository.findById(CustomPlanSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepository.save(CustomPlanSettings.builder()
                        .id(CustomPlanSettings.SINGLETON_ID)
                        .enabled(false)
                        .pricePerHour(new BigDecimal("20"))
                        .bandwidth("5M/5M")
                        .minMinutes(10)
                        .maxMinutes(1440)
                        .build()));
    }

    @Transactional
    public CustomPlanSettings update(CustomPlanSettings updated) {
        if (updated.getMinMinutes() < 1 || updated.getMaxMinutes() < updated.getMinMinutes()) {
            throw new IllegalArgumentException("Minutes range is invalid");
        }
        if (updated.getPricePerHour() == null || updated.getPricePerHour().signum() <= 0) {
            throw new IllegalArgumentException("Price per hour must be positive");
        }
        updated.setId(CustomPlanSettings.SINGLETON_ID);
        return settingsRepository.save(updated);
    }

    /** Whole-shilling price for the requested minutes (M-Pesa needs whole KES, min 1). */
    public BigDecimal priceFor(int minutes, CustomPlanSettings settings) {
        BigDecimal price = settings.getPricePerHour()
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.CEILING);
        return price.max(BigDecimal.ONE);
    }

    /** The hidden plan row custom payments and vouchers hang off. */
    @Transactional
    public Plan systemPlan(CustomPlanSettings settings) {
        Plan plan = planRepository.findByName(SYSTEM_PLAN_NAME)
                .orElseGet(() -> planRepository.save(Plan.builder()
                        .name(SYSTEM_PLAN_NAME)
                        .price(BigDecimal.ONE)
                        .durationMinutes(1)
                        .active(false)
                        .build()));
        if (settings.getBandwidth() != null && !settings.getBandwidth().equals(plan.getBandwidth())) {
            plan.setBandwidth(settings.getBandwidth());
            plan = planRepository.save(plan);
        }
        return plan;
    }
}
