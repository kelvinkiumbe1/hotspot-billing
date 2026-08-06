package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.FiberRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FiberRouteRepository extends JpaRepository<FiberRoute, Long> {

    List<FiberRoute> findByStatus(FiberRoute.Status status);

    List<FiberRoute> findByFromNodeIdOrToNodeId(Long fromNodeId, Long toNodeId);
}
