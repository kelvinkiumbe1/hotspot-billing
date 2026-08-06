package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.FiberNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FiberNodeRepository extends JpaRepository<FiberNode, Long> {

    List<FiberNode> findByKind(FiberNode.Kind kind);

    List<FiberNode> findByStatus(FiberNode.Status status);

    List<FiberNode> findByParentId(Long parentId);
}
