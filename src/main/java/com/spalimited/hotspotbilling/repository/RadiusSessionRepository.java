package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.RadiusSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RadiusSessionRepository extends JpaRepository<RadiusSession, Long> {

    Optional<RadiusSession> findByNasAddressAndAcctSessionId(String nasAddress, String acctSessionId);

    List<RadiusSession> findByStoppedAtIsNullOrderByStartedAtDesc();

    List<RadiusSession> findByUsernameAndStoppedAtIsNull(String username);

    List<RadiusSession> findByVoucherIdAndStoppedAtIsNull(Long voucherId);

    List<RadiusSession> findByStoppedAtIsNullAndLastUpdateAtBefore(Instant cutoff);

    List<RadiusSession> findTop100ByOrderByStartedAtDesc();

    long countByStoppedAtIsNull();
}
