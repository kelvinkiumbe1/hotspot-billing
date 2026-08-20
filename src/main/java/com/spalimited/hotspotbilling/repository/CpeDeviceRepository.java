package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CpeDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CpeDeviceRepository extends JpaRepository<CpeDevice, Long> {

    Optional<CpeDevice> findByOuiAndSerialNumber(String oui, String serialNumber);

    List<CpeDevice> findBySubscriberId(Long subscriberId);

    List<CpeDevice> findAllByOrderByLastInformAtDesc();
}
