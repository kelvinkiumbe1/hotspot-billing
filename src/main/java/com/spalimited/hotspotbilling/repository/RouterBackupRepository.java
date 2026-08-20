package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.RouterBackup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RouterBackupRepository extends JpaRepository<RouterBackup, Long> {

    List<RouterBackup> findByRouterIdOrderByFirstSeenAtDesc(Long routerId);

    /** The version the router is believed to be running now. */
    Optional<RouterBackup> findFirstByRouterIdOrderByFirstSeenAtDesc(Long routerId);

    List<RouterBackup> findAllByOrderByLastSeenAtDesc();

    long countByRouterId(Long routerId);
}
