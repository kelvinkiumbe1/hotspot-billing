package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.MigrationRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MigrationRowRepository extends JpaRepository<MigrationRow, Long> {

    List<MigrationRow> findByBatchIdOrderByIdAsc(Long batchId);

    List<MigrationRow> findByBatchIdAndVerdictOrderByIdAsc(Long batchId, MigrationRow.Verdict verdict);

    long countByBatchIdAndVerdict(Long batchId, MigrationRow.Verdict verdict);

    void deleteByBatchId(Long batchId);
}
