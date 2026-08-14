package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.JobHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobHeartbeatRepository extends JpaRepository<JobHeartbeat, String> {
}
