package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, NotificationTemplate.Key> {
}
