package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CpeParameter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CpeParameterRepository extends JpaRepository<CpeParameter, Long> {

    List<CpeParameter> findByCpeDeviceId(Long cpeDeviceId);

    Optional<CpeParameter> findByCpeDeviceIdAndName(Long cpeDeviceId, String name);
}
