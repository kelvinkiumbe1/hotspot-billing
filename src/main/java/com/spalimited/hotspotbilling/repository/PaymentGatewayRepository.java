package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentGatewayRepository extends JpaRepository<PaymentGateway, Long> {

    Optional<PaymentGateway> findByKind(PaymentGateway.Kind kind);

    Optional<PaymentGateway> findByActiveTrue();

    List<PaymentGateway> findAllByOrderByKindAsc();
}
