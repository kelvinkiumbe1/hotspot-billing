package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Turns router failures into incidents, and incidents into something the
 * customer actually hears about.
 *
 * <p>Routers fail in groups — a power cut, an uplink, a backhaul — so device
 * alerts arrive in bursts about a single cause. Worse, the customer side of an
 * outage has always been silence: they discover it themselves, then ring to ask,
 * and the operator spends the outage answering the same question. Nothing in
 * this product ever told them first.
 *
 * <p>So simultaneous failures are grouped into one incident, and after a short
 * settling delay — most blips fix themselves, and a message about a two-minute
 * drop is worse than no message — the customers <em>on those routers</em> are
 * told once, with an estimate. Not everybody: telling the unaffected there is a
 * fault manufactures a problem they did not have. A ticket is opened for
 * whoever is fixing it, and on recovery the same people get the all-clear and
 * their time back automatically.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    /**
     * Failures within this window are treated as one event. Routers rarely drop
     * in the same poll; a shared cause still takes a minute to propagate.
     */
    private static final Duration GROUPING_WINDOW = Duration.ofMinutes(15);

    private final IncidentRepository incidents;
    private final RouterRepository routers;
    private final VoucherRepository vouchers;
    private final SupportTicketRepository tickets;
    private final SubscriptionService subscriptions;
    private final OperatorAlertSettingsService alertSettings;
    private final PortalSettingsService portalSettings;
    private final SmsService smsService;
    private final AuditService audit;

    // --- Router transitions, called by the monitor ---

    /** Files a router that has just gone down, joining any incident in flight. */
    @Transactional
    public Incident routerDown(Router router) {
        Instant now = Instant.now();
        Incident open = incidents.findFirstByStatusOrderByStartedAtDesc(Incident.Status.OPEN)
                .filter(i -> i.getStartedAt().isAfter(now.minus(GROUPING_WINDOW)))
                .orElse(null);

        if (open == null) {
            open = incidents.save(Incident.builder()
                    .status(Incident.Status.OPEN)
                    .startedAt(now)
                    .title(router.getName() + " is down")
                    .routerIds(new LinkedHashSet<>(Set.of(router.getId())))
                    .build());
            audit.system("incident.open", "Incident #" + open.getId() + " opened: " + open.getTitle());
            log.info("Opened incident {} for {}", open.getId(), router.getName());
            return open;
        }

        if (open.getRouterIds().add(router.getId())) {
            open.setTitle(titleFor(open.getRouterIds()));
            incidents.save(open);
            audit.system("incident.join", router.getName() + " joined incident #" + open.getId());
        }
        return open;
    }

    /**
     * Files a router coming back. The incident closes only once every router in
     * it is up — a partial recovery is not an all-clear, and telling customers
     * it is over while half the network is still down is worse than saying
     * nothing at all.
     */
    @Transactional
    public void routerUp(Router router) {
        for (Incident incident : incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN)) {
            if (!incident.getRouterIds().contains(router.getId())) {
                continue;
            }
            boolean allBack = incident.getRouterIds().stream()
                    .map(id -> routers.findById(id).orElse(null))
                    .filter(Objects::nonNull)
                    .allMatch(Router::isOnline);
            if (!allBack) {
                log.info("{} is back but incident {} still has routers down", router.getName(), incident.getId());
                return;
            }
            resolve(incident);
            return;
        }
    }

    // --- Telling people ---

    /**
     * Sends the customer notice for any incident that has now lasted past the
     * settling delay. Runs on a timer rather than at the moment of failure so
     * short drops pass unremarked.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    @Transactional
    public void notifyRipeIncidents() {
        OperatorAlertSettings settings = alertSettings.get();
        if (!settings.isCustomerOutageNotice()) {
            return;
        }
        Instant ripe = Instant.now().minus(Duration.ofMinutes(settings.getOutageNotifyAfterMinutes()));
        for (Incident incident : incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN)) {
            if (incident.getNotifiedAt() != null || incident.getStartedAt().isAfter(ripe)) {
                continue;
            }
            notifyAffected(incident, settings);
        }
    }

    private void notifyAffected(Incident incident, OperatorAlertSettings settings) {
        String business = business();
        String where = placeOf(incident.getRouterIds());
        String message = business + ": we're sorry — internet in " + where + " is down and our team is on it. "
                + "We expect to be back within " + settings.getOutageEtaMinutes() + " minutes. "
                + "You won't lose the time you've paid for.";

        int sent = 0;
        for (String phone : affectedPhones(incident.getRouterIds())) {
            smsService.trySend(phone, message);
            sent++;
        }

        incident.setNotifiedAt(Instant.now());
        incident.setNotifiedCount(sent);
        // The messages have already gone out by this point, so nothing after
        // them may throw: an exception here would roll back notifiedAt and the
        // next sweep would text every affected customer a second time. A ticket
        // that failed to open is worth a log line, not a duplicate apology.
        if (incident.getTicketId() == null) {
            try {
                SupportTicket ticket = tickets.save(SupportTicket.builder()
                        .customerName("Network")
                        .phoneNumber("")
                        .subject(incident.getTitle())
                        .priority(SupportTicket.Priority.HIGH)
                        .status(SupportTicket.Status.OPEN)
                        .createdBy("system")
                        .build());
                incident.setTicketId(ticket == null ? null : ticket.getId());
            } catch (Exception e) {
                log.warn("Could not open a ticket for incident {}: {}", incident.getId(), e.getMessage());
            }
        }
        incidents.save(incident);
        audit.system("incident.notify", "Told " + sent + " customer(s) about incident #" + incident.getId());
        log.info("Incident {}: notified {} affected customer(s)", incident.getId(), sent);
    }

    /**
     * Closes an incident: the all-clear to the same people who were warned, and
     * their time back. Compensation is scoped to the routers that were down,
     * because crediting the whole customer base for a fault on one site is
     * generous but wrong.
     */
    private void resolve(Incident incident) {
        OperatorAlertSettings settings = alertSettings.get();
        Instant now = Instant.now();
        incident.setStatus(Incident.Status.RESOLVED);
        incident.setEndedAt(now);

        Duration downtime = Duration.between(incident.getStartedAt(), now);
        if (settings.isOutageCompensationEnabled() && downtime.toMinutes() >= settings.getMinOutageMinutes()) {
            try {
                int credited = subscriptions.compensateForOutage(downtime, incident.getRouterIds());
                incident.setCompensatedMinutes(downtime.toMinutes());
                incident.setCompensatedCount(credited);
            } catch (Exception e) {
                log.warn("Compensation for incident {} failed: {}", incident.getId(), e.getMessage());
            }
        }

        // Only the people who were told there was a problem get told it is over.
        if (incident.getNotifiedAt() != null) {
            String message = business() + ": internet in " + placeOf(incident.getRouterIds())
                    + " is back. Thank you for your patience"
                    + (incident.getCompensatedCount() > 0
                            ? " — we've added " + downtime.toMinutes() + " minutes to your expiry." : ".");
            for (String phone : affectedPhones(incident.getRouterIds())) {
                smsService.trySend(phone, message);
            }
            incident.setResolvedNotifiedAt(now);
        }

        if (incident.getTicketId() != null) {
            tickets.findById(incident.getTicketId()).ifPresent(t -> {
                t.setStatus(SupportTicket.Status.RESOLVED);
                t.setResolvedAt(now);
                t.setResolvedBy("system");
                tickets.save(t);
            });
        }

        incidents.save(incident);
        audit.system("incident.resolve", "Incident #" + incident.getId() + " resolved after "
                + downtime.toMinutes() + " minutes"
                + (incident.getCompensatedCount() > 0
                        ? "; credited " + incident.getCompensatedCount() + " subscriber(s)" : ""));
        log.info("Incident {} resolved after {} minutes", incident.getId(), downtime.toMinutes());
    }

    // --- Who is affected ---

    /**
     * Everybody on the affected routers: fixed-line subscribers, plus hotspot
     * customers holding a pass that is still good. A spent pass is not an
     * affected customer — they were not going to be online anyway.
     */
    private Set<String> affectedPhones(Set<Long> routerIds) {
        Set<String> phones = new LinkedHashSet<>();
        for (Subscriber sub : subscriptions.affectedBy(routerIds)) {
            if (sub.getPhoneNumber() != null && !sub.getPhoneNumber().isBlank()) {
                phones.add(sub.getPhoneNumber());
            }
        }
        for (Voucher v : vouchers.findByStatusIn(List.of(Voucher.Status.UNUSED, Voucher.Status.ACTIVE))) {
            if (v.getPhoneNumber() == null || v.getPhoneNumber().isBlank() || v.isExhausted()) {
                continue;
            }
            if (v.getRouterId() != null && routerIds.contains(v.getRouterId())) {
                phones.add(v.getPhoneNumber());
            }
        }
        return phones;
    }

    // --- The public status page ---

    /**
     * What is wrong right now and what has been wrong lately, with no customer
     * detail in it. Published so people can check before they ring — the
     * cheapest support the operator will ever provide.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> publicStatus() {
        OperatorAlertSettings settings = alertSettings.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business", business());
        if (!settings.isStatusPageEnabled()) {
            out.put("enabled", false);
            return out;
        }
        List<Incident> open = incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN);
        out.put("enabled", true);
        out.put("operational", open.isEmpty());
        out.put("etaMinutes", settings.getOutageEtaMinutes());
        out.put("current", open.stream().map(this::publicView).toList());
        out.put("recent", incidents.findByStartedAtAfterOrderByStartedAtDesc(
                        Instant.now().minus(Duration.ofDays(14))).stream()
                .filter(i -> i.getStatus() == Incident.Status.RESOLVED)
                .limit(10)
                .map(this::publicView)
                .toList());
        return out;
    }

    private Map<String, Object> publicView(Incident incident) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", incident.getId());
        row.put("area", placeOf(incident.getRouterIds()));
        row.put("startedAt", incident.getStartedAt());
        row.put("endedAt", incident.getEndedAt());
        row.put("minutes", incident.getDuration().toMinutes());
        row.put("resolved", incident.getStatus() == Incident.Status.RESOLVED);
        return row;
    }

    // --- Admin view ---

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN));
        out.put("recent", incidents.findTop20ByOrderByStartedAtDesc());
        out.put("settings", alertSettings.get());
        return out;
    }

    // --- helpers ---

    /** Names the incident by where it is, which is what a customer recognises. */
    private String titleFor(Set<Long> routerIds) {
        List<String> names = routerIds.stream()
                .map(id -> routers.findById(id).map(Router::getName).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        if (names.isEmpty()) {
            return "Network outage";
        }
        if (names.size() == 1) {
            return names.get(0) + " is down";
        }
        return names.size() + " sites are down (" + String.join(", ", names) + ")";
    }

    /** The customer-facing place name: a router's location, or its name. */
    private String placeOf(Set<Long> routerIds) {
        List<String> places = routerIds.stream()
                .map(id -> routers.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .map(r -> r.getLocation() != null && !r.getLocation().isBlank() ? r.getLocation() : r.getName())
                .distinct()
                .toList();
        if (places.isEmpty()) {
            return "your area";
        }
        if (places.size() == 1) {
            return places.get(0);
        }
        return String.join(" and ", places.subList(0, Math.min(2, places.size())))
                + (places.size() > 2 ? " and other areas" : "");
    }

    private String business() {
        String biz = portalSettings.settings().getBusinessName();
        return biz == null || biz.isBlank() ? "Your ISP" : biz;
    }
}
