package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Optional<Voucher> findByCode(String code);

    boolean existsByCode(String code);

    List<Voucher> findByStatusAndExpiresAtBefore(Voucher.Status status, Instant cutoff);

    long countByStatus(Voucher.Status status);

    List<Voucher> findTop100ByOrderByCreatedAtDesc();
}
