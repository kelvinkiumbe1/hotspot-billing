package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.TrialClaim;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrialClaimRepository extends JpaRepository<TrialClaim, String> {
}
