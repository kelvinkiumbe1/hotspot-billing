package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Referral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReferralRepository extends JpaRepository<Referral, String> {

    Optional<Referral> findByCode(String code);

    /** Leaderboard for the admin view. */
    List<Referral> findTop50BySuccessfulReferralsGreaterThanOrderBySuccessfulReferralsDesc(int min);
}
