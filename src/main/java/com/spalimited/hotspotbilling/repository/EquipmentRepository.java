package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    List<Equipment> findAllByOrderByCreatedAtDesc();

    List<Equipment> findByStatus(Equipment.Status status);

    List<Equipment> findByTechnicianId(Long technicianId);

    List<Equipment> findBySubscriberId(Long subscriberId);

    Optional<Equipment> findBySerialNumber(String serialNumber);
}
