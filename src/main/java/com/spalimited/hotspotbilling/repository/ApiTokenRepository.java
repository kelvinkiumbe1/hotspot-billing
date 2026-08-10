package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    Optional<ApiToken> findByToken(String token);

    List<ApiToken> findAllByOrderByCreatedAtDesc();
}
