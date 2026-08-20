package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.SubscriberUsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SubscriberUsageDailyRepository extends JpaRepository<SubscriberUsageDaily, Long> {

    Optional<SubscriberUsageDaily> findBySubscriberIdAndDay(Long subscriberId, LocalDate day);

    List<SubscriberUsageDaily> findBySubscriberIdAndDayBetweenOrderByDayAsc(
            Long subscriberId, LocalDate from, LocalDate to);

    /**
     * One subscriber's total over a range, in bytes.
     *
     * <p>Summed in the database rather than by loading rows because the cap check
     * runs on every accounting update for every online customer, and a month is
     * thirty-one rows each time.
     */
    @Query("""
            SELECT COALESCE(SUM(u.bytesUp + u.bytesDown), 0) FROM SubscriberUsageDaily u
            WHERE u.subscriberId = :subscriberId AND u.day >= :from AND u.day <= :to
            """)
    long totalBytes(@Param("subscriberId") Long subscriberId,
                    @Param("from") LocalDate from,
                    @Param("to") LocalDate to);

    /** Every subscriber's total over a range: [subscriberId, bytesUp, bytesDown]. */
    @Query("""
            SELECT u.subscriberId, SUM(u.bytesUp), SUM(u.bytesDown)
            FROM SubscriberUsageDaily u
            WHERE u.day >= :from AND u.day <= :to
            GROUP BY u.subscriberId
            ORDER BY SUM(u.bytesUp + u.bytesDown) DESC
            """)
    List<Object[]> totalsBySubscriber(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Whole-network daily totals over a range: [day, bytesUp, bytesDown]. */
    @Query("""
            SELECT u.day, SUM(u.bytesUp), SUM(u.bytesDown) FROM SubscriberUsageDaily u
            WHERE u.day >= :from AND u.day <= :to
            GROUP BY u.day ORDER BY u.day ASC
            """)
    List<Object[]> dailyTotals(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long deleteByDayBefore(LocalDate cutoff);
}
