package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Keyed by message and language, so a bilingual operator can hold both
 * wordings at once instead of choosing which half of their customers to
 * disappoint.
 */
public interface NotificationTemplateRepository
        extends JpaRepository<NotificationTemplate, NotificationTemplate.TemplateId> {

    List<NotificationTemplate> findByLanguage(String language);
}
