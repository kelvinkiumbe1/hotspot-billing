package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CallSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CallSettingsRepository extends JpaRepository<CallSettings, Long> {
}
