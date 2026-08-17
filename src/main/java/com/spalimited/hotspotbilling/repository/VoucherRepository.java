package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Optional<Voucher> findByCode(String code);

    boolean existsByCode(String code);

    List<Voucher> findByStatusAndExpiresAtBefore(Voucher.Status status, Instant cutoff);

    /** Passes whose wall-clock deadline has gone by, whether used yet or not. */
    List<Voucher> findByStatusInAndExpiresAtBefore(
            java.util.Collection<Voucher.Status> statuses, Instant cutoff);

    /** Vouchers that should exist on the router — issued or in use, not spent. */
    List<Voucher> findByStatusIn(java.util.Collection<Voucher.Status> statuses);

    /** Used to auto-expire vouchers printed but never used after a set age. */
    List<Voucher> findByStatusAndCreatedAtBefore(Voucher.Status status, Instant cutoff);

    long countByStatus(Voucher.Status status);

    List<Voucher> findTop100ByOrderByCreatedAtDesc();

    List<Voucher> findByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    /** Everything issued since a cutoff — the revenue audit's look-back window. */
    List<Voucher> findByCreatedAtAfter(Instant cutoff);

    /** Devices a pass is locked to; a router user named after one of these is ours. */
    @org.springframework.data.jpa.repository.Query(
            "select v.boundMac from Voucher v where v.boundMac is not null")
    List<String> findAllBoundMacs();

    /**
     * Active passes about to run out that haven't been nudged yet — the source
     * for the "your WiFi is almost up, buy more" WhatsApp/SMS reminder.
     */
    List<Voucher> findByStatusAndNudgedAtIsNullAndExpiresAtBetween(
            Voucher.Status status, Instant from, Instant to);

    /**
     * Active passes on a data-capped plan that have burned through at least the
     * threshold share of their cap and haven't had a data nudge yet. Kept in
     * integer math to stay type-safe: {@code threshold = thresholdPercent *
     * bytesPerMb}, and both sides are cross-multiplied by 100 to fold in the
     * percentage without any fractional parameter.
     */
    @org.springframework.data.jpa.repository.Query(
            "select v from Voucher v where v.status = :status"
                    + " and v.dataNudgedAt is null and v.phoneNumber is not null"
                    + " and v.plan.dataLimitMb is not null and v.plan.dataLimitMb > 0"
                    + " and v.usedBytes * 100 >= v.plan.dataLimitMb * :threshold")
    List<Voucher> findDataNudgeCandidates(
            @org.springframework.data.repository.query.Param("status") Voucher.Status status,
            @org.springframework.data.repository.query.Param("threshold") long threshold);
}
