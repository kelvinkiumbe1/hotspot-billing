package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.LoyaltySettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltySettingsRepository extends JpaRepository<LoyaltySettings, Long> {
}
