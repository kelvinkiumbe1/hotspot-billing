package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.ManualClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ManualClaimRepository extends JpaRepository<ManualClaim, Long> {

    Optional<ManualClaim> findByReceipt(String receipt);

    Optional<ManualClaim> findByConversationId(String conversationId);
}
