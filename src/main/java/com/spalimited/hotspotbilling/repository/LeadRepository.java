package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Lead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeadRepository extends JpaRepository<Lead, Long> {

    List<Lead> findAllByOrderByCreatedAtDesc();

    List<Lead> findByStatus(Lead.Status status);
}
