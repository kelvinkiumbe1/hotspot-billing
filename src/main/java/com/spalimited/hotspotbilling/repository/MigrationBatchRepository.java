package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.MigrationBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MigrationBatchRepository extends JpaRepository<MigrationBatch, Long> {

    List<MigrationBatch> findAllByOrderByCreatedAtDesc();
}
