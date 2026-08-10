package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.TrafficUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TrafficUsageRepository extends JpaRepository<TrafficUsage, Long> {

    /** The accumulating row for one user, hour and router — the capture cursor. */
    Optional<TrafficUsage> findByBucketHourAndRouterIdAndUserKey(Instant bucketHour, Long routerId, String userKey);

    /** All traffic since a cutoff — aggregated in the service for each report. */
    List<TrafficUsage> findByBucketHourGreaterThanEqual(Instant from);
}
