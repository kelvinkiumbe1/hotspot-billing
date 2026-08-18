package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.DeliveredSpeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DeliveredSpeedRepository extends JpaRepository<DeliveredSpeed, Long> {

    Optional<DeliveredSpeed> findBySubscriberIdAndObservedOn(Long subscriberId, LocalDate observedOn);

    List<DeliveredSpeed> findByObservedOnGreaterThanEqualOrderByObservedOnDesc(LocalDate from);

    List<DeliveredSpeed> findBySubscriberIdAndObservedOnGreaterThanEqualOrderByObservedOnDesc(
            Long subscriberId, LocalDate from);
}
