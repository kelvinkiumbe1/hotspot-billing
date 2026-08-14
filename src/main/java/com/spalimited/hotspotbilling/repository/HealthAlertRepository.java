package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.HealthAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HealthAlertRepository extends JpaRepository<HealthAlert, Long> {

    Optional<HealthAlert> findByCheckKey(String checkKey);

    List<HealthAlert> findByStatus(HealthAlert.Status status);

    List<HealthAlert> findTop50ByOrderByLastSeenAtDesc();
}
