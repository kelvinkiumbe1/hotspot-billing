package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.LedgerAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerAdjustmentRepository extends JpaRepository<LedgerAdjustment, Long> {

    List<LedgerAdjustment> findBySubscriberIdOrderByAppliedOnAsc(Long subscriberId);
}
