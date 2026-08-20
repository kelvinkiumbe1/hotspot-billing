package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    Optional<BankTransaction> findByDedupeKey(String dedupeKey);

    boolean existsByDedupeKey(String dedupeKey);

    List<BankTransaction> findByImportIdOrderByValueDateAsc(Long importId);

    /** The work queue: everything still waiting on a person. */
    List<BankTransaction> findByStatusInOrderByValueDateAsc(List<BankTransaction.Status> statuses);

    long countByStatus(BankTransaction.Status status);
}
