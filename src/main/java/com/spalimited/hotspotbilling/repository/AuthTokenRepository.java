package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    Optional<AuthToken> findByToken(String token);

    List<AuthToken> findByStaffUserIdOrderByIssuedAtDesc(Long staffUserId);

    @Modifying
    @Query("delete from AuthToken t where t.expiresAt < :cutoff")
    int deleteExpired(Instant cutoff);

    @Modifying
    @Query("delete from AuthToken t where t.staffUserId = :staffUserId")
    int deleteForUser(Long staffUserId);
}
