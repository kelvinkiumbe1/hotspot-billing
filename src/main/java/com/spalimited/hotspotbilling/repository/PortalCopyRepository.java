package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.PortalCopy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortalCopyRepository extends JpaRepository<PortalCopy, Long> {

    List<PortalCopy> findByLanguage(String language);

    Optional<PortalCopy> findByLanguageAndCopyKey(String language, String copyKey);
}
