package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.C2bPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface C2bPaymentRepository extends JpaRepository<C2bPayment, Long> {

    Optional<C2bPayment> findByTransactionId(String transactionId);

    List<C2bPayment> findTop200ByOrderByCreatedAtDesc();

    List<C2bPayment> findByStatusOrderByCreatedAtDesc(C2bPayment.Status status);
}
