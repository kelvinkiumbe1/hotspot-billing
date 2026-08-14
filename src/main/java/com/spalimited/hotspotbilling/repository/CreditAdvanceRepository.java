package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CreditAdvance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CreditAdvanceRepository extends JpaRepository<CreditAdvance, Long> {

    List<CreditAdvance> findByPhoneNumberAndStatus(String phoneNumber, CreditAdvance.Status status);

    List<CreditAdvance> findByPhoneNumberOrderByIssuedAtDesc(String phoneNumber);

    long countByPhoneNumberAndStatus(String phoneNumber, CreditAdvance.Status status);

    List<CreditAdvance> findByStatusOrderByDueAtAsc(CreditAdvance.Status status);

    List<CreditAdvance> findByStatusAndDueAtBefore(CreditAdvance.Status status, Instant cutoff);
}
