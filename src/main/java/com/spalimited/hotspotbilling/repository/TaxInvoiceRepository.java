package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.TaxInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, Long> {

    List<TaxInvoice> findTop200ByOrderByCreatedAtDesc();

    long countByStatus(TaxInvoice.Status status);
}
