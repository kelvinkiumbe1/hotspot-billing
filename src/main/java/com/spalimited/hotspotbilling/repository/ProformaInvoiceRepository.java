package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.ProformaInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProformaInvoiceRepository extends JpaRepository<ProformaInvoice, Long> {

    long countByNumberStartingWith(String prefix);

    List<ProformaInvoice> findBySubscriberIdOrderByIssuedOnDesc(Long subscriberId);

    List<ProformaInvoice> findTop200ByOrderByIssuedOnDesc();

    List<ProformaInvoice> findByStatus(ProformaInvoice.Status status);
}
