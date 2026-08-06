package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PortalSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalSettingsRepository extends JpaRepository<PortalSettings, Long> {
}
