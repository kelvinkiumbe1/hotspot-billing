package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NetworkDeviceRepository extends JpaRepository<NetworkDevice, Long> {

    List<NetworkDevice> findByEnabledTrue();

    List<NetworkDevice> findAllByOrderByNameAsc();

    Optional<NetworkDevice> findByName(String name);

    long countByEnabledTrueAndOnlineFalse();
}
