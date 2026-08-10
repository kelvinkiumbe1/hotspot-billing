package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    List<Webhook> findByActiveTrue();

    List<Webhook> findAllByOrderByCreatedAtDesc();
}
