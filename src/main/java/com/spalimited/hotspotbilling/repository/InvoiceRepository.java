package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByNumber(String number);

    List<Invoice> findTop200ByOrderByIssuedOnDesc();

    List<Invoice> findByStatusOrderByDueOnAsc(Invoice.Status status);

    List<Invoice> findBySubscriberIdOrderByIssuedOnDesc(Long subscriberId);

    Optional<Invoice> findFirstBySubscriberIdAndStatusOrderByDueOnAsc(Long subscriberId, Invoice.Status status);

    List<Invoice> findByIssuedOnBetween(LocalDate from, LocalDate to);

    long countByNumberStartingWith(String prefix);

    /** Invoiced total per subscriber, cancelled ones excluded. */
    @org.springframework.data.jpa.repository.Query(
            "select i.subscriber.id, coalesce(sum(i.amount), 0) from Invoice i "
            + "where i.status <> com.spalimited.hotspotbilling.domain.Invoice$Status.CANCELLED "
            + "group by i.subscriber.id")
    java.util.List<Object[]> totalInvoicedPerSubscriber();

}
