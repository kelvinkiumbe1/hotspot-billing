package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    Optional<SubscriptionPayment> findByCheckoutRequestId(String checkoutRequestId);

    List<SubscriptionPayment> findBySubscriberIdOrderByCreatedAtDesc(Long subscriberId);

    void deleteBySubscriberId(Long subscriberId);

    // --- Sales digest ---

    @Query("select coalesce(sum(p.amount), 0) from SubscriptionPayment p "
            + "where p.status = :status and p.completedAt >= :since")
    BigDecimal sumAmountByStatusSince(@Param("status") SubscriptionPayment.Status status, @Param("since") Instant since);

    long countByStatusAndCompletedAtAfter(SubscriptionPayment.Status status, Instant since);

    /** Closed window for the briefing's week-on-week comparison. */
    @Query("select coalesce(sum(p.amount), 0) from SubscriptionPayment p "
            + "where p.status = :status and p.completedAt >= :from and p.completedAt < :to")
    BigDecimal sumAmountByStatusBetween(@Param("status") SubscriptionPayment.Status status,
                                        @Param("from") Instant from, @Param("to") Instant to);
}
