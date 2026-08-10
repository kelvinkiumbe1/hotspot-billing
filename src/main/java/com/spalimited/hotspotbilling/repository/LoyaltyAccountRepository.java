package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.LoyaltyAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, String> {
}
