package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.RouterMove;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.RouterMoveRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moving customers between routers, and replacing a router that has died.
 *
 * <p>Both were manual: a dead router meant editing customers one at a time, and
 * splitting a site across a second box meant the same. On a two-hundred-customer
 * router that is an afternoon of clicking with a mistake in it somewhere.
 *
 * <h2>Half-failure is the normal case</h2>
 *
 * <p>The new router accepts twelve of twenty and then stops answering. So nothing
 * here is all-or-nothing: each customer is moved independently, the ones that
 * worked stay moved, and the ones that did not are named. A transaction spanning
 * the whole batch would roll back eleven successful moves because of the twelfth,
 * leaving the operator no better off and the router holding secrets for customers
 * the database says are elsewhere.
 *
 * <p>Which is also why every attempt is recorded. Without a record the eight that
 * failed are a discrepancy somebody finds weeks later; with one they are a list
 * to retry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouterFleetService {

    private final RouterRepository routers;
    private final SubscriberRepository subscribers;
    private final RouterMoveRepository moves;
    private final MikrotikService mikrotikService;
    private final AuditService audit;

    /** What a move did. */
    public record Outcome(long moveId, int moved, int failed, List<String> problems,
                          String message) {
    }

    /**
     * Moves specific customers onto another router.
     *
     * <p>Provisions on the destination BEFORE removing from the source, for the
     * same reason a rename does: the reverse order leaves a customer with no
     * secret anywhere if the second call fails, and the failure mode of this
     * order is a stale secret nobody uses.
     */
    @Transactional
    public Outcome transfer(List<Long> subscriberIds, Long toRouterId, String who) {
        Router destination = routers.findById(toRouterId)
                .orElseThrow(() -> new IllegalArgumentException("No such destination router"));
        if (!mikrotikService.manageable(destination)) {
            throw new IllegalStateException(destination.getName()
                    + " is switched off, so nothing can be moved onto it");
        }
        RouterMove move = moves.save(RouterMove.builder()
                .kind(RouterMove.Kind.TRANSFER)
                .toRouterId(toRouterId)
                .startedAt(Instant.now())
                .startedBy(who)
                .build());

        List<String> problems = new ArrayList<>();
        int moved = 0;
        for (Long id : subscriberIds) {
            Subscriber sub = subscribers.findById(id).orElse(null);
            if (sub == null) {
                problems.add("customer " + id + " no longer exists");
                continue;
            }
            if (toRouterId.equals(sub.getRouterId())) {
                // Already there. Not a failure and not work.
                continue;
            }
            try {
                moveOne(sub, destination);
                moved++;
            } catch (Exception e) {
                problems.add(sub.getFullName() + " (" + sub.getPppoeUsername() + "): "
                        + e.getMessage());
            }
        }
        return finish(move, moved, problems,
                "Moved " + moved + " customer(s) onto " + destination.getName());
    }

    /**
     * Moves everything off one router onto another.
     *
     * <p>For the box that has died. The old router is disabled rather than
     * deleted: its configuration backup, its history and its name are the only
     * record of what the network looked like, and somebody will want them while
     * working out what happened.
     */
    @Transactional
    public Outcome replace(Long fromRouterId, Long toRouterId, boolean copySettings, String who) {
        if (fromRouterId.equals(toRouterId)) {
            throw new IllegalArgumentException("Those are the same router");
        }
        Router from = routers.findById(fromRouterId)
                .orElseThrow(() -> new IllegalArgumentException("No such router to replace"));
        Router to = routers.findById(toRouterId)
                .orElseThrow(() -> new IllegalArgumentException("No such replacement router"));
        if (!mikrotikService.manageable(to)) {
            throw new IllegalStateException(to.getName()
                    + " is switched off, so it cannot take anybody over");
        }

        RouterMove move = moves.save(RouterMove.builder()
                .kind(RouterMove.Kind.REPLACE)
                .fromRouterId(fromRouterId)
                .toRouterId(toRouterId)
                .startedAt(Instant.now())
                .startedBy(who)
                .build());

        if (copySettings) {
            // The site's identity rather than its address: which branch it serves
            // and what its uplink can carry are properties of the place, not of
            // the box, and losing them means capacity planning silently loses a
            // site.
            to.setBranchId(from.getBranchId());
            to.setCapacityMbps(from.getCapacityMbps());
            to.setLocation(from.getLocation());
            routers.save(to);
        }

        List<String> problems = new ArrayList<>();
        int moved = 0;
        for (Subscriber sub : subscribers.findAll()) {
            if (!fromRouterId.equals(sub.getRouterId())) {
                continue;
            }
            try {
                moveOne(sub, to);
                moved++;
            } catch (Exception e) {
                problems.add(sub.getFullName() + " (" + sub.getPppoeUsername() + "): "
                        + e.getMessage());
            }
        }

        // Only once everybody is off it. Disabling a router that still has
        // customers pointed at it would strand them: every later call would skip
        // it as unmanageable and nothing would say why.
        if (problems.isEmpty()) {
            from.setEnabled(false);
            from.setLastError("Replaced by " + to.getName() + " on " + Instant.now());
            routers.save(from);
        }

        audit.record(who, "router.replace", from.getName() + " replaced by " + to.getName()
                + ": " + moved + " customer(s) moved"
                + (problems.isEmpty() ? "" : ", " + problems.size() + " failed"));

        return finish(move, moved, problems, problems.isEmpty()
                ? "Moved " + moved + " customer(s) to " + to.getName() + " and switched "
                        + from.getName() + " off."
                : "Moved " + moved + " customer(s), but " + problems.size() + " failed — "
                        + from.getName() + " has been left switched ON so the rest can be "
                        + "retried.");
    }

    /**
     * One customer onto one router.
     *
     * <p>Provision first, then remove, then save. The order is the point: the
     * database is only told the customer has moved once the destination has
     * actually accepted them.
     */
    private void moveOne(Subscriber sub, Router destination) {
        Long oldRouterId = sub.getRouterId();
        Subscriber onDestination = copyFor(sub, destination.getId());
        mikrotikService.provisionPppoe(onDestination);

        if (oldRouterId != null && !oldRouterId.equals(destination.getId())) {
            try {
                mikrotikService.removePppoe(copyFor(sub, oldRouterId));
            } catch (Exception e) {
                // A stale secret on the old box is untidy; failing the move over
                // it would leave the customer provisioned in two places and the
                // database pointing at neither.
                log.warn("Left a stale PPPoE secret for {} on router {}: {}",
                        sub.getPppoeUsername(), oldRouterId, e.getMessage());
            }
        }

        sub.setRouterId(destination.getId());
        subscribers.save(sub);
    }

    /**
     * The same customer as seen from one particular router.
     *
     * <p>A detached copy, so provisioning against the destination cannot mutate
     * the row before the destination has accepted it -- and so removing from the
     * old router uses the old id rather than whatever the entity now holds.
     */
    private static Subscriber copyFor(Subscriber sub, Long routerId) {
        return Subscriber.builder()
                .id(sub.getId())
                .fullName(sub.getFullName())
                .pppoeUsername(sub.getPppoeUsername())
                .pppoePassword(sub.getPppoePassword())
                .bandwidth(sub.getBandwidth())
                .staticIp(sub.getStaticIp())
                .routerId(routerId)
                .build();
    }

    private Outcome finish(RouterMove move, int moved, List<String> problems, String message) {
        move.setFinishedAt(Instant.now());
        move.setMovedCount(moved);
        move.setFailedCount(problems.size());
        String detail = String.join("\n", problems);
        move.setDetail(detail.length() > 3990 ? detail.substring(0, 3990) : detail);
        moves.save(move);
        return new Outcome(move.getId(), moved, problems.size(), problems, message);
    }

    /** How many customers each router carries, for choosing a destination. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> fleet() {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Subscriber s : subscribers.findAll()) {
            if (s.getRouterId() != null) {
                counts.merge(s.getRouterId(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Router r : routers.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("name", r.getName());
            row.put("host", r.getHost());
            row.put("enabled", r.isEnabled());
            row.put("online", r.isOnline());
            row.put("customers", counts.getOrDefault(r.getId(), 0));
            row.put("capacityMbps", r.getCapacityMbps());
            row.put("defaultRouter", r.isDefaultRouter());
            out.add(row);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<RouterMove> recentMoves() {
        return moves.findTop50ByOrderByStartedAtDesc();
    }
}
