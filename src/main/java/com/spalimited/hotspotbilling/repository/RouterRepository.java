package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Router;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RouterRepository extends JpaRepository<Router, Long> {

    Optional<Router> findByName(String name);

    Optional<Router> findFirstByDefaultRouterTrue();

    List<Router> findAllByOrderByNameAsc();

    List<Router> findByEnabledTrue();
}
