package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.RadiusClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RadiusClientRepository extends JpaRepository<RadiusClient, Long> {

    List<RadiusClient> findByEnabledTrue();

    List<RadiusClient> findAllByOrderByNameAsc();

    Optional<RadiusClient> findByAddress(String address);
}
