package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PayoutRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayoutRequestRepository extends JpaRepository<PayoutRequest, Long> {

    List<PayoutRequest> findAllByOrderByCreatedAtDesc();

    List<PayoutRequest> findByTechnicianOrderByCreatedAtDesc(String technician);
}
