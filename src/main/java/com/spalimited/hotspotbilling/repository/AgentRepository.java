package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByCode(String code);

    List<Agent> findAllByOrderByFullNameAsc();
}
