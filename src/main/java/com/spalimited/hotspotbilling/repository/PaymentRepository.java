package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByCheckoutRequestId(String checkoutRequestId);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.status = :status")
    BigDecimal totalAmountByStatus(@Param("status") Payment.Status status);

    long countByStatus(Payment.Status status);

    List<Payment> findTop100ByOrderByCreatedAtDesc();
}
