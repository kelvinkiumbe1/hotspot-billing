package com.spalimited.controlplane;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformInvoiceRepository extends JpaRepository<PlatformInvoice, Long> {

    List<PlatformInvoice> findByTenantSlugOrderByCreatedAtDesc(String tenantSlug);

    Optional<PlatformInvoice> findByCheckoutId(String checkoutId);

    Optional<PlatformInvoice> findFirstByTenantSlugAndPeriodAndStatus(
            String tenantSlug, String period, PlatformInvoice.Status status);
}
