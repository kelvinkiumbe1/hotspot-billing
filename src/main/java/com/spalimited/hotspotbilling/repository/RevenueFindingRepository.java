package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.RevenueFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RevenueFindingRepository extends JpaRepository<RevenueFinding, Long> {

    Optional<RevenueFinding> findByFingerprint(String fingerprint);

    List<RevenueFinding> findByStatus(RevenueFinding.Status status);

    List<RevenueFinding> findTop300ByOrderByLastSeenAtDesc();
}
