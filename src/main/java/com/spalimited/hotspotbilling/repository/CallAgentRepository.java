package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CallAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallAgentRepository extends JpaRepository<CallAgent, Long> {

    List<CallAgent> findByActiveTrueOrderByPriorityAsc();

    List<CallAgent> findAllByOrderByPriorityAsc();
}
