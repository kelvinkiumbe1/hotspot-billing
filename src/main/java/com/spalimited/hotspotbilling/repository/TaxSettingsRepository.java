package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.TaxSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxSettingsRepository extends JpaRepository<TaxSettings, Long> {
}
