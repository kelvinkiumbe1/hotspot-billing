package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CallAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallAgentRepository extends JpaRepository<CallAgent, Long> {

    List<CallAgent> findByActiveTrueOrderByPriorityAsc();

    List<CallAgent> findAllByOrderByPriorityAsc();

    java.util.Optional<com.spalimited.hotspotbilling.domain.CallAgent> findByTechnicianId(Long technicianId);

    /**
     * The rota for calls coming IN.
     *
     * <p>Technicians are deliberately absent: they can place a call from the
     * business number without a customer ringing the business and reaching
     * somebody up a ladder.
     */
    java.util.List<com.spalimited.hotspotbilling.domain.CallAgent>
            findByActiveTrueAndInboundTrueOrderByPriorityAsc();
}
