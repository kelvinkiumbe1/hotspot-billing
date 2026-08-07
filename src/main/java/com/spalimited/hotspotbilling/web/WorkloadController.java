package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.*;
import java.util.*;

/**
 * Who is out on a job, and how much each technician has actually done.
 *
 * <p>Everything here is derived from the tickets themselves rather than from
 * a separate timesheet, so it cannot disagree with the work it describes and
 * nobody has to remember to fill anything in.
 */
@RestController
@RequestMapping("/api/admin/workload")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CUSTOMERS')")
public class WorkloadController {

    private final TechnicianRepository technicians;
    private final SupportTicketRepository tickets;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> workload() {
        List<SupportTicket> all = tickets.findAll();
        List<Map<String, Object>> out = new ArrayList<>();

        for (Technician tech : technicians.findAllByOrderByCreatedAtAsc()) {
            List<SupportTicket> mine = all.stream()
                    .filter(t -> t.getAssigneeIds().contains(tech.getId()))
                    .toList();

            List<SupportTicket> open = mine.stream()
                    .filter(t -> t.getStatus() != SupportTicket.Status.RESOLVED)
                    .toList();

            // Days worked counts distinct calendar days on which they closed
            // something. Counting every day a ticket sat open would credit
            // them for weekends a job merely spanned.
            Set<LocalDate> daysClosed = new TreeSet<>();
            long totalMinutes = 0;
            int closed = 0;
            for (SupportTicket t : mine) {
                if (t.getResolvedAt() != null && tech.getFullName().equals(t.getResolvedBy())) {
                    daysClosed.add(t.getResolvedAt().atZone(ZONE).toLocalDate());
                    closed++;
                    if (t.getWorkStartedAt() != null) {
                        totalMinutes += Duration.between(t.getWorkStartedAt(), t.getResolvedAt()).toMinutes();
                    }
                }
            }

            // The longest-running open job is what "on a job since…" means;
            // a technician holding three tickets is out until the first is done.
            Instant since = open.stream()
                    .map(SupportTicket::getWorkStartedAt)
                    .filter(Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(null);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tech.getId());
            row.put("fullName", tech.getFullName());
            row.put("username", tech.getUsername());
            row.put("phoneNumber", tech.getPhoneNumber());
            row.put("active", tech.isActive());

            row.put("onJob", !open.isEmpty());
            row.put("openJobs", open.size());
            row.put("workingSince", since);
            row.put("workingMinutes", since == null ? null
                    : Duration.between(since, Instant.now()).toMinutes());
            row.put("currentJobs", open.stream()
                    .map(t -> Map.<String, Object>of(
                            "id", t.getId(),
                            "subject", t.getSubject(),
                            "customer", t.getCustomerName(),
                            "since", t.getWorkStartedAt() == null ? "" : t.getWorkStartedAt().toString()))
                    .toList());

            row.put("jobsClosed", closed);
            row.put("daysWorked", daysClosed.size());
            row.put("lastWorkedOn", daysClosed.isEmpty() ? null
                    : ((TreeSet<LocalDate>) daysClosed).last().toString());
            row.put("averageMinutesPerJob", closed == 0 ? null : totalMinutes / closed);
            out.add(row);
        }

        // Whoever is out on a job first — that is who the office is waiting on.
        out.sort((a, b) -> Boolean.compare(
                Boolean.FALSE.equals(a.get("onJob")), Boolean.FALSE.equals(b.get("onJob"))));
        return out;
    }

    /** Every day this technician closed work, most recent first. */
    @GetMapping("/{id}/days")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> days(@PathVariable Long id) {
        Technician tech = technicians.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown technician: " + id));

        Map<LocalDate, List<String>> byDay = new TreeMap<>(Comparator.reverseOrder());
        for (SupportTicket t : tickets.findAll()) {
            if (t.getResolvedAt() == null || !tech.getFullName().equals(t.getResolvedBy())) {
                continue;
            }
            byDay.computeIfAbsent(t.getResolvedAt().atZone(ZONE).toLocalDate(), k -> new ArrayList<>())
                    .add(t.getSubject());
        }

        return byDay.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "date", e.getKey().toString(),
                        "jobs", e.getValue().size(),
                        "subjects", e.getValue()))
                .toList();
    }
}
