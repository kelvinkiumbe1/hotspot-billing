package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    List<Incident> findByStatusOrderByStartedAtDesc(Incident.Status status);

    Optional<Incident> findFirstByStatusOrderByStartedAtDesc(Incident.Status status);

    List<Incident> findTop20ByOrderByStartedAtDesc();

    List<Incident> findByStartedAtAfterOrderByStartedAtDesc(Instant since);
}
