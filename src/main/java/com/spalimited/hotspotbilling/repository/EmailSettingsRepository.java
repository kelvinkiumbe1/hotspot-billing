package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.EmailSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSettingsRepository extends JpaRepository<EmailSettings, Long> {
}
