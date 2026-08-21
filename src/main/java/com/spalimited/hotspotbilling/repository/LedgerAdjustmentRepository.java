package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.LedgerAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerAdjustmentRepository extends JpaRepository<LedgerAdjustment, Long> {

    List<LedgerAdjustment> findBySubscriberIdOrderByAppliedOnAsc(Long subscriberId);

    /**
     * Adjustments per subscriber, split by kind.
     *
     * <p>Split rather than summed, because the sign lives in Java
     * ({@code getSignedAmount}: a penalty adds, everything else reduces) and
     * duplicating that rule into SQL is how the two drift apart.
     */
    @org.springframework.data.jpa.repository.Query(
            "select a.subscriber.id, a.kind, coalesce(sum(a.amount), 0) "
            + "from LedgerAdjustment a group by a.subscriber.id, a.kind")
    java.util.List<Object[]> totalAdjustedPerSubscriberAndKind();

}
