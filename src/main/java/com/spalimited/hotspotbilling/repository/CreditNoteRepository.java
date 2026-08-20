package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

    long countByNumberStartingWith(String prefix);

    List<CreditNote> findBySubscriberIdOrderByIssuedOnDesc(Long subscriberId);

    List<CreditNote> findTop200ByOrderByIssuedOnDesc();

    List<CreditNote> findByInvoiceId(Long invoiceId);

    /**
     * How much of one invoice has already been credited.
     *
     * <p>Summed in the database because it gates every new credit note against
     * that invoice, and issuing two that together exceed the invoice is the
     * mistake this exists to prevent.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COALESCE(SUM(c.amount), 0) FROM CreditNote c WHERE c.invoiceId = :invoiceId
            """)
    Optional<BigDecimal> creditedAgainst(
            @org.springframework.data.repository.query.Param("invoiceId") Long invoiceId);
}
