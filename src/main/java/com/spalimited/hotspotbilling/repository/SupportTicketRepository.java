package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findTop100ByOrderByUpdatedAtDesc();

    /** Everything still live, for the field sweeps and a technician's job list. */
    List<SupportTicket> findByStatusInOrderByCreatedAtAsc(Collection<SupportTicket.Status> statuses);

    long countByResolvedByAndResolvedAtAfter(String resolvedBy, Instant since);
}
