package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

import java.util.List;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByActiveTrueOrderByPriceAsc();

    Optional<Plan> findByName(String name);
}
