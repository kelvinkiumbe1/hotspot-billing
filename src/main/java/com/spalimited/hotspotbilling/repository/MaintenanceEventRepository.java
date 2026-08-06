package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.MaintenanceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaintenanceEventRepository extends JpaRepository<MaintenanceEvent, Long> {

    List<MaintenanceEvent> findAllByOrderByScheduledStartAsc();
}
