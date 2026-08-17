package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByCheckoutRequestId(String checkoutRequestId);

    /** Still-pending payments — the reconciliation sweep queries these. */
    List<Payment> findByStatus(Payment.Status status);

    /** A customer's payments, newest first — for phone-number self-recovery. */
    List<Payment> findByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.status = :status")
    BigDecimal totalAmountByStatus(@Param("status") Payment.Status status);

    long countByStatus(Payment.Status status);

    List<Payment> findTop100ByOrderByCreatedAtDesc();

    // --- Sales digest: what completed since the start of the day ---

    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.status = :status and p.completedAt >= :since")
    BigDecimal sumAmountByStatusSince(@Param("status") Payment.Status status, @Param("since") java.time.Instant since);

    long countByStatusAndCompletedAtAfter(Payment.Status status, java.time.Instant since);

    /**
     * A closed window, so the briefing can compare today against the same day
     * last week. Half-open [from, to) — otherwise a payment landing exactly on
     * midnight is counted in both days.
     */
    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.status = :status and p.completedAt >= :from and p.completedAt < :to")
    BigDecimal sumAmountByStatusBetween(@Param("status") Payment.Status status,
                                        @Param("from") java.time.Instant from,
                                        @Param("to") java.time.Instant to);

    // --- Revenue audit ---

    List<Payment> findByStatusAndCreatedAtAfter(Payment.Status status, java.time.Instant since);

    /** Vouchers a payment is attached to — the audit's "this one was paid for" set. */
    @Query("select p.voucher.id from Payment p where p.voucher is not null")
    List<Long> findAllVoucherIds();
}
