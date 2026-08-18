package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.DeviceInterface;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceInterfaceRepository extends JpaRepository<DeviceInterface, Long> {

    List<DeviceInterface> findByDeviceIdOrderByIfIndexAsc(Long deviceId);

    Optional<DeviceInterface> findByDeviceIdAndIfIndex(Long deviceId, Integer ifIndex);

    List<DeviceInterface> findByMonitoredTrueAndOperUpFalse();

    void deleteByDeviceId(Long deviceId);
}
