package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CallRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {

    Optional<CallRecord> findBySessionId(String sessionId);

    List<CallRecord> findTop200ByOrderByStartedAtDesc();

    List<CallRecord> findBySubscriberIdOrderByStartedAtDesc(Long subscriberId);

    List<CallRecord> findByStatusInOrderByStartedAtDesc(List<CallRecord.Status> statuses);

    /** Live calls for one agent: what the screen pop polls for. */
    List<CallRecord> findByAgentIdAndStatusInOrderByStartedAtDesc(
            Long agentId, List<CallRecord.Status> statuses);

    long countByStatusAndStartedAtAfter(CallRecord.Status status, Instant since);
}
