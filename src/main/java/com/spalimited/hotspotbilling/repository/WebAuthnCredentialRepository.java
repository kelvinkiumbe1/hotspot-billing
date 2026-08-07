package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.WebAuthnCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebAuthnCredentialRepository extends JpaRepository<WebAuthnCredential, Long> {

    List<WebAuthnCredential> findByStaffUserId(Long staffUserId);

    Optional<WebAuthnCredential> findByCredentialId(String credentialId);

    long countByStaffUserId(Long staffUserId);

    void deleteByStaffUserId(Long staffUserId);
}
