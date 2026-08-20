package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Subscriber;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
