package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.RetentionScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RetentionScoreRepository extends JpaRepository<RetentionScore, Long> {

    Optional<RetentionScore> findBySubscriberId(Long subscriberId);

    List<RetentionScore> findByBandInOrderByScoreDesc(Collection<RetentionScore.Band> bands);

    long countByBand(RetentionScore.Band band);

    Optional<RetentionScore> findTopByOrderByScoredAtDesc();
}
