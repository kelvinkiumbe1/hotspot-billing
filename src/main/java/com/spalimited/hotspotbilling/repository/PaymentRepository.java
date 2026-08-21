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

    /**
     * A payment whose stored provider reference begins with this.
     *
     * <p>For Orange Money, whose status query needs a pay token, an order id and
     * an amount together — so all three are stored in the one reference column,
     * order id first. Its notification quotes only the order id, and this is how
     * that becomes the pay token needed to ask Orange what happened.
     */
    Optional<Payment> findFirstByCheckoutRequestIdStartingWith(String prefix);

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

    /**
     * Successful takings per day since a date, added up by the database.
     *
     * <p>The overview used to read every payment ever made into memory to draw a
     * fortnight's sparkline. That is free at twenty-four customers and takes
     * eight seconds at five thousand -- on the first screen every member of
     * staff opens every morning.
     *
     * <p>CAST rather than date() so the same query runs on H2 in the tests.
     */
    @Query(value = "select cast(coalesce(completed_at, created_at) as date) as day, "
            + "coalesce(sum(amount), 0) as total from payments "
            + "where status = 'SUCCESS' and coalesce(completed_at, created_at) >= :since "
            + "group by 1", nativeQuery = true)
    java.util.List<Object[]> dailyTotalsSince(@Param("since") java.time.Instant since);

}
