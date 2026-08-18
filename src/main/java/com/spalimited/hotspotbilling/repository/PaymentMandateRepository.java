package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PaymentMandate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMandateRepository extends JpaRepository<PaymentMandate, Long> {

    Optional<PaymentMandate> findBySubscriberId(Long subscriberId);

    Optional<PaymentMandate> findByExternalRef(String externalRef);

    List<PaymentMandate> findByStatus(PaymentMandate.Status status);

    long countByStatus(PaymentMandate.Status status);
}
