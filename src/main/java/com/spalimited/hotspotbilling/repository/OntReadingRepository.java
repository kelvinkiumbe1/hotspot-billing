package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.OntReading;
import com.spalimited.hotspotbilling.service.snmp.OpticalPower;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OntReadingRepository extends JpaRepository<OntReading, Long> {

    List<OntReading> findByOltDeviceId(Long oltDeviceId);

    Optional<OntReading> findByOltDeviceIdAndSerial(Long oltDeviceId, String serial);

    List<OntReading> findByHealthIn(List<OpticalPower.Health> health);

    List<OntReading> findBySubscriberId(Long subscriberId);

    long countByHealthIn(List<OpticalPower.Health> health);
}
