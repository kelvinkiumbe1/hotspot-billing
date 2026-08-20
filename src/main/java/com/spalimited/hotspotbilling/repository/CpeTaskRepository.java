package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CpeTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CpeTaskRepository extends JpaRepository<CpeTask, Long> {

    List<CpeTask> findByCpeDeviceIdAndStatusOrderByIdAsc(Long cpeDeviceId, CpeTask.Status status);

    List<CpeTask> findByCpeDeviceIdOrderByIdDesc(Long cpeDeviceId);

    long countByStatus(CpeTask.Status status);
}
