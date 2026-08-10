package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.HotspotSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotspotSettingsRepository extends JpaRepository<HotspotSettings, Long> {
}
