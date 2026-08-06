package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.VoucherBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VoucherBatchRepository extends JpaRepository<VoucherBatch, Long> {

    List<VoucherBatch> findAllByOrderByCreatedAtDesc();

    List<VoucherBatch> findByAgentId(Long agentId);

    long countByReferenceStartingWith(String prefix);
}
