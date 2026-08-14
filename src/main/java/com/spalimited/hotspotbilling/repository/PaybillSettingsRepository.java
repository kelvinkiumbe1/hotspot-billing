package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PaybillSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaybillSettingsRepository extends JpaRepository<PaybillSettings, Long> {
}
