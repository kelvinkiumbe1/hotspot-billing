package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.RadiusSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RadiusSessionRepository extends JpaRepository<RadiusSession, Long> {

    Optional<RadiusSession> findByNasAddressAndAcctSessionId(String nasAddress, String acctSessionId);

    List<RadiusSession> findByStoppedAtIsNullOrderByStartedAtDesc();

    List<RadiusSession> findByUsernameAndStoppedAtIsNull(String username);

    List<RadiusSession> findByVoucherIdAndStoppedAtIsNull(Long voucherId);

    List<RadiusSession> findByStoppedAtIsNullAndLastUpdateAtBefore(Instant cutoff);

    List<RadiusSession> findTop100ByOrderByStartedAtDesc();

    long countByStoppedAtIsNull();

    List<RadiusSession> findByStartedAtBetween(Instant from, Instant to);

    /**
     * Everything one subscriber moved in a window.
     *
     * <p>Summed in the database rather than in Java: a busy PPPoE customer can
     * have hundreds of sessions a month, and pulling them all back to add two
     * columns is the kind of query that is fine on a demo and unusable on a
     * real book of customers.
     */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(s.inOctets + s.outOctets), 0) from RadiusSession s "
                    + "where s.subscriberId = :subscriberId "
                    + "and s.startedAt >= :from and s.startedAt < :to")
    long totalOctetsBetween(@org.springframework.data.repository.query.Param("subscriberId") Long subscriberId,
                            @org.springframework.data.repository.query.Param("from") Instant from,
                            @org.springframework.data.repository.query.Param("to") Instant to);
}
