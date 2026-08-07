package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.MessagingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessagingSettingsRepository extends JpaRepository<MessagingSettings, Long> {
}
