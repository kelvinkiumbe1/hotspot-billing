package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Technician;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {

    Optional<Technician> findByUsername(String username);

    Optional<Technician> findByUsernameAndActiveTrue(String username);

    List<Technician> findAllByOrderByCreatedAtAsc();
}
