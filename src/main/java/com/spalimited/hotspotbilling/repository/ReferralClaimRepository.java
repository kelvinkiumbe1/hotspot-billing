package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.ReferralClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralClaimRepository extends JpaRepository<ReferralClaim, Long> {

    Optional<ReferralClaim> findByRefereePhone(String refereePhone);

    long countByStatus(ReferralClaim.Status status);

    List<ReferralClaim> findTop50ByOrderByCreatedAtDesc();
}
