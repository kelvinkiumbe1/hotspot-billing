package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CommissionPayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommissionPayoutRepository extends JpaRepository<CommissionPayout, Long> {

    List<CommissionPayout> findTop200ByOrderByCreatedAtDesc();

    List<CommissionPayout> findByStatusInOrderByCreatedAtAsc(Collection<CommissionPayout.Status> statuses);

    List<CommissionPayout> findByAgentIdOrderByCreatedAtDesc(Long agentId);

    Optional<CommissionPayout> findByConversationId(String conversationId);
}
