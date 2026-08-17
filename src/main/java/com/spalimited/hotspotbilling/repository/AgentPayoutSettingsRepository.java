package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.AgentPayoutSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentPayoutSettingsRepository extends JpaRepository<AgentPayoutSettings, Long> {
}
