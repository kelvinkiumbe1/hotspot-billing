package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Subscriber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {

    Optional<Subscriber> findByPppoeUsername(String pppoeUsername);

    List<Subscriber> findAllByOrderByCreatedAtAsc();

    List<Subscriber> findByStatus(Subscriber.Status status);

    List<Subscriber> findByPhoneNumber(String phoneNumber);
}
