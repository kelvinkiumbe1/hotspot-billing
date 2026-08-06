package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    Optional<Promotion> findFirstByStartsAtBeforeAndEndsAtAfterOrderByCreatedAtDesc(Instant now, Instant sameNow);

    List<Promotion> findTop20ByOrderByCreatedAtDesc();
}
