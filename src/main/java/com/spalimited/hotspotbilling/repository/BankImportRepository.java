package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.BankImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankImportRepository extends JpaRepository<BankImport, Long> {

    List<BankImport> findAllByOrderByUploadedAtDesc();
}
