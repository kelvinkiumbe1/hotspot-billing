package com.spalimited.controlplane;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlug(String slug);

    /** For completing a signup from the link in the owner's email. */
    Optional<Tenant> findByVerificationToken(String verificationToken);

    boolean existsBySlug(String slug);

    boolean existsBySubdomain(String subdomain);

    boolean existsByOwnerEmail(String ownerEmail);

    java.util.Optional<Tenant> findByOwnerEmail(String ownerEmail);

    List<Tenant> findAllByOrderByCreatedAtDesc();
}
