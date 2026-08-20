package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.RouterMove;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RouterMoveRepository extends JpaRepository<RouterMove, Long> {

    List<RouterMove> findTop50ByOrderByStartedAtDesc();
}
