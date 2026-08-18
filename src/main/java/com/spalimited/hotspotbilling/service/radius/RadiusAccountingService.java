package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.RadiusSession;
import com.spalimited.hotspotbilling.domain.TrafficUsage;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.RadiusSessionRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.TrafficUsageRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * What the router tells us about a session while it is running and when it ends.
 *
 * <p>This replaces polling, and is better than it in one specific way: a
 * customer who connects and disconnects between two polls is invisible to a
 * poller and fully recorded here. It is also worse in one specific way — if the
 * router dies mid-session, the Stop never arrives, and the last interim update
 * is all there is. That is what bounds the interim interval.
 *
 * <p>The rule that matters throughout: accounting packets are retransmitted
 * whenever a reply is lost, and a NAS returning from a reboot will replay them.
 * So every figure is applied as a high-water mark, never added. Adding would
 * charge a customer twice for one session, and the retransmission that caused
 * it would leave no trace.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RadiusAccountingService {

    private final RadiusSessionRepository sessions;
    private final VoucherRepository vouchers;
    private final SubscriberRepository subscribers;
    private final TrafficUsageRepository trafficUsage;

    /** Everything one accounting packet said, already parsed. */
    public record Report(String nasAddress, String acctSessionId, String username,
                         int statusType, long inOctets, long outOctets, long sessionSeconds,
                         String framedIp, String callingStation, String calledStation,
                         String nasPortId, String terminateCause, Long routerId) {
    }

    @Transactional
    public void record(Report report, RadiusSession.Kind kind, Long voucherId, Long subscriberId) {
        RadiusSession session = sessions
                .findByNasAddressAndAcctSessionId(report.nasAddress(), report.acctSessionId())
                .orElse(null);

        Instant now = Instant.now();
        if (session == null) {
            // An interim or a Stop for a session we never saw start — normal
            // after a restart of this service, and the alternative to recording
            // it is losing the usage entirely.
            session = RadiusSession.builder()
                    .nasAddress(report.nasAddress())
                    .acctSessionId(report.acctSessionId())
                    .username(report.username())
                    .kind(kind == null ? RadiusSession.Kind.HOTSPOT : kind)
                    .voucherId(voucherId)
                    .subscriberId(subscriberId)
                    .startedAt(now.minusSeconds(Math.max(0, report.sessionSeconds())))
                    .lastUpdateAt(now)
                    .build();
        }

        session.setLastUpdateAt(now);
        if (report.framedIp() != null) {
            session.setFramedIp(report.framedIp());
        }
        if (report.callingStation() != null) {
            session.setCallingStation(report.callingStation());
        }
        if (report.calledStation() != null) {
            session.setCalledStation(report.calledStation());
        }
        if (report.nasPortId() != null) {
            session.setNasPortId(report.nasPortId());
        }
        if (session.getVoucherId() == null) {
            session.setVoucherId(voucherId);
        }
        if (session.getSubscriberId() == null) {
            session.setSubscriberId(subscriberId);
        }

        // Counters only ever climb within one session, so a lower figure is a
        // packet arriving out of order and is ignored rather than believed.
        session.setInOctets(Math.max(session.getInOctets(), report.inOctets()));
        session.setOutOctets(Math.max(session.getOutOctets(), report.outOctets()));
        session.setSessionSeconds(Math.max(session.getSessionSeconds(), report.sessionSeconds()));

        if (report.statusType() == RadiusPacket.ACCT_STOP) {
            if (session.getStoppedAt() == null) {
                session.setStoppedAt(now);
            }
            session.setTerminateCause(report.terminateCause());
        }

        applyUsage(session, report.routerId());
        sessions.save(session);
    }

    /**
     * Folds the part of this session that has not been counted yet into the
     * customer's own totals.
     *
     * <p>The difference between the session's figures and what has already been
     * applied is the only thing added, and the applied mark moves up with it.
     * A replayed packet therefore contributes nothing, which is the entire
     * point — the alternative is a customer's pass draining twice as fast as
     * they used it, with nothing in the record to show why.
     */
    private void applyUsage(RadiusSession session, Long routerId) {
        long newSeconds = session.getSessionSeconds() - session.getAppliedSeconds();
        long newOctets = (session.getInOctets() + session.getOutOctets()) - session.getAppliedOctets();
        if (newSeconds <= 0 && newOctets <= 0) {
            return;
        }

        if (session.getVoucherId() != null) {
            vouchers.findById(session.getVoucherId()).ifPresent(voucher -> {
                if (newSeconds > 0) {
                    voucher.setUsedSeconds(voucher.getUsedSeconds() + newSeconds);
                }
                if (newOctets > 0) {
                    voucher.setUsedBytes(voucher.getUsedBytes() + newOctets);
                }
                // A code redeemed at a hotspot login was never marked used
                // until something observed it. This is that observation.
                if (voucher.getStatus() == Voucher.Status.UNUSED) {
                    voucher.setStatus(Voucher.Status.ACTIVE);
                    if (voucher.getActivatedAt() == null) {
                        voucher.setActivatedAt(Instant.now());
                    }
                    if (voucher.getExpiresAt() == null) {
                        voucher.setExpiresAt(Instant.now().plusSeconds(voucher.getDurationSeconds()));
                    }
                }
                if (voucher.getStatus() == Voucher.Status.ACTIVE && voucher.isExhausted()) {
                    voucher.setStatus(Voucher.Status.EXPIRED);
                }
                if (voucher.getRouterId() == null && routerId != null) {
                    voucher.setRouterId(routerId);
                }
                vouchers.save(voucher);
            });
        } else if (session.getSubscriberId() != null && newOctets > 0) {
            subscribers.findById(session.getSubscriberId()).ifPresent(subscriber -> {
                subscriber.setDataUsedMb(subscriber.getDataUsedMbOrZero() + newOctets / 1_048_576L);
                subscriber.setLastSeenOnlineAt(Instant.now());
                subscribers.save(subscriber);
            });
        }

        if (newOctets > 0) {
            recordTraffic(session, routerId, newOctets);
        }

        session.setAppliedSeconds(Math.max(session.getAppliedSeconds(), session.getSessionSeconds()));
        session.setAppliedOctets(Math.max(session.getAppliedOctets(),
                session.getInOctets() + session.getOutOctets()));
    }

    /**
     * Adds to the same hourly table the router poller fills, so every existing
     * usage report keeps working without knowing where the numbers came from.
     */
    private void recordTraffic(RadiusSession session, Long routerId, long newOctets) {
        if (routerId == null) {
            return;
        }
        // Split in the same proportion the session reports, so the up/down
        // split stays honest rather than being invented.
        long total = session.getInOctets() + session.getOutOctets();
        long up = total == 0 ? 0 : newOctets * session.getInOctets() / total;
        long down = newOctets - up;

        Instant bucket = Instant.now().truncatedTo(ChronoUnit.HOURS);
        TrafficUsage row = trafficUsage
                .findByBucketHourAndRouterIdAndUserKey(bucket, routerId, session.getUsername())
                .orElseGet(() -> TrafficUsage.builder()
                        .bucketHour(bucket).routerId(routerId).userKey(session.getUsername())
                        .bytesUp(0).bytesDown(0).build());
        row.setBytesUp(row.getBytesUp() + up);
        row.setBytesDown(row.getBytesDown() + down);
        trafficUsage.save(row);
    }

    /**
     * Closes sessions whose router stopped talking.
     *
     * <p>A NAS that reboots or loses power never sends a Stop, so without this
     * the session stays open forever: the customer looks permanently connected,
     * their device count stays used up, and nothing ever reconciles. Three
     * missed interims is the threshold — one lost packet is normal.
     */
    @Transactional
    public int closeAbandoned(int interimSeconds) {
        Instant cutoff = Instant.now().minusSeconds(Math.max(600L, interimSeconds * 3L));
        int closed = 0;
        for (RadiusSession session : sessions.findByStoppedAtIsNullAndLastUpdateAtBefore(cutoff)) {
            session.setStoppedAt(session.getLastUpdateAt());
            // Named so it is never mistaken for the customer having logged out.
            session.setTerminateCause("No updates from the router");
            sessions.save(session);
            closed++;
        }
        if (closed > 0) {
            log.info("Closed {} RADIUS session(s) whose router stopped reporting", closed);
        }
        return closed;
    }

    /** True when a subscriber is currently online, per the router's own reports. */
    @Transactional(readOnly = true)
    public boolean isOnline(String username) {
        return !sessions.findByUsernameAndStoppedAtIsNull(username).isEmpty();
    }

    /** How many logins are live right now. */
    @Transactional(readOnly = true)
    public long liveCount() {
        return sessions.countByStoppedAtIsNull();
    }
}
