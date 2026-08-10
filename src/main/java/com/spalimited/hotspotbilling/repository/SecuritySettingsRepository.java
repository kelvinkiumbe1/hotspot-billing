package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.SecuritySettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecuritySettingsRepository extends JpaRepository<SecuritySettings, Long> {
}
