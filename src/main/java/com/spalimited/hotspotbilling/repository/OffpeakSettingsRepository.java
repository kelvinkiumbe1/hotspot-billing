package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.OffpeakSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OffpeakSettingsRepository extends JpaRepository<OffpeakSettings, Long> {
}
