package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Subscriber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {

    Optional<Subscriber> findByPppoeUsername(String pppoeUsername);

    List<Subscriber> findAllByOrderByCreatedAtAsc();

    List<Subscriber> findByStatus(Subscriber.Status status);

    List<Subscriber> findByPhoneNumber(String phoneNumber);

    /** Subscribers with an active dunning cycle whose next retry is now due. */
    List<Subscriber> findByDunningCycleIsNotNullAndDunningNextAtLessThanEqual(java.time.Instant now);

    /** Lapsed subscribers in a win-back series whose next message is now due. */
    List<Subscriber> findByWinbackCycleIsNotNullAndWinbackNextAtLessThanEqual(java.time.Instant now);

    /**
     * Everyone the fair-use sweep has to look at: capped customers, plus anyone
     * still carrying a mark from a cap that has since been removed. Leaving the
     * second half out would strand those customers throttled forever.
     */
    List<Subscriber> findByDataCapMbIsNotNullOrFupAppliedAtIsNotNull();

    /**
     * Who the router poll has seen recently.
     *
     * <p>Replaces reading the whole book and filtering in Java, which is the
     * difference between a query and a table scan once an ISP has a few thousand
     * customers.
     */
    java.util.List<Subscriber> findByLastSeenOnlineAtAfter(java.time.Instant since);


    /**
     * One page of customers, narrowed and searched by the database.
     *
     * <p>The list endpoint used to return every customer as a full entity --
     * 4.1 MB of JSON at five thousand of them, and the slowest thing in the
     * product. Filtering and searching in the browser only works while the whole
     * book fits in it.
     *
     * <p>The branch is part of the query rather than a filter applied afterwards.
     * Filtering a page after the database has already chosen it would hand a
     * branch login a half-empty page and a wrong total -- and, worse, page two
     * would skip customers rather than show them.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM Subscriber s
            WHERE (:branch IS NULL OR s.branchId = :branch)
              AND (:status IS NULL OR s.status = :status)
              AND (:q IS NULL
                   OR LOWER(s.fullName) LIKE :q
                   OR LOWER(s.pppoeUsername) LIKE :q
                   OR s.phoneNumber LIKE :q)
            """)
    org.springframework.data.domain.Page<Subscriber> search(
            @Param("branch") Long branch,
            @Param("status") Subscriber.Status status,
            @Param("q") String q,
            org.springframework.data.domain.Pageable pageable);

    /**
     * The figures the stat cards show, counted by the database.
     *
     * <p>They were added up in the browser from the full list, which is the same
     * reason the full list had to be sent in the first place.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT s.status, COUNT(s), COALESCE(SUM(s.monthlyFee), 0)
            FROM Subscriber s
            WHERE (:branch IS NULL OR s.branchId = :branch)
            GROUP BY s.status
            """)
    java.util.List<Object[]> countAndValueByStatus(@Param("branch") Long branch);

    /** Active customers whose paid-up date lands inside the window. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(s) FROM Subscriber s
            WHERE (:branch IS NULL OR s.branchId = :branch)
              AND s.status = com.spalimited.hotspotbilling.domain.Subscriber$Status.ACTIVE
              AND s.paidUntil IS NOT NULL AND s.paidUntil <= :before
            """)
    long countExpiringBefore(@Param("branch") Long branch, @Param("before") java.time.Instant before);

    /**
     * Just enough of each customer to fill a picker.
     *
     * <p>Thirty-odd screens load the customer list only to populate a dropdown,
     * and were each pulling every column of every row to show a name. Seven
     * fields instead of thirty-five -- the five a picker needs, plus the monthly
     * fee two screens price against and the router the fleet screen groups by,
     * so every one of them can use this and none has to fall back to the heavy
     * endpoint.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT s.id, s.fullName, s.phoneNumber, s.pppoeUsername, s.status,
                   s.monthlyFee, s.routerId
            FROM Subscriber s
            WHERE (:branch IS NULL OR s.branchId = :branch)
            ORDER BY s.fullName ASC
            """)
    java.util.List<Object[]> lookup(@Param("branch") Long branch);

}
