package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PayCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayCodeRepository extends JpaRepository<PayCode, String> {

    /** The live code already showing on this device, so a refresh doesn't churn it. */
    Optional<PayCode> findFirstByMacAddressAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String macAddress, Instant now);

    List<PayCode> findByExpiresAtBeforeAndUsedAtIsNull(Instant cutoff);

    /** MACs we have handed a live code to — the revenue audit treats these as ours. */
    @org.springframework.data.jpa.repository.Query(
            "select p.macAddress from PayCode p where p.macAddress is not null")
    List<String> findAllMacAddresses();
}
