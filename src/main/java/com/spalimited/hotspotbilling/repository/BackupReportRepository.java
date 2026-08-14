package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.BackupReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BackupReportRepository extends JpaRepository<BackupReport, Long> {

    List<BackupReport> findTop50ByOrderByReportedAtDesc();

    Optional<BackupReport> findFirstByOkTrueOrderByReportedAtDesc();

    Optional<BackupReport> findFirstByOrderByReportedAtDesc();

    List<BackupReport> findByReportedAtAfterOrderByReportedAtDesc(Instant since);
}
