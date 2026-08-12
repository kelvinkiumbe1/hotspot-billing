package com.spalimited.controlplane;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySubdomain(String subdomain);

    List<Tenant> findAllByOrderByCreatedAtDesc();
}
