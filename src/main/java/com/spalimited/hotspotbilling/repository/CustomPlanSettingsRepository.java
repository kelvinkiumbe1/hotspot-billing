package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CustomPlanSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomPlanSettingsRepository extends JpaRepository<CustomPlanSettings, Long> {
}
