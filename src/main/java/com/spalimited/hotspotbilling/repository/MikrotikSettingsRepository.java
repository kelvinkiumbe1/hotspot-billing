package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.MikrotikSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MikrotikSettingsRepository extends JpaRepository<MikrotikSettings, Long> {
}
