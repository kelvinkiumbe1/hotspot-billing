package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.domain.TicketMessage;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.FieldOpsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Support tickets. Customers open tickets through the public
 * POST /api/tickets endpoint; everything under /api/admin/tickets is for
 * staff (HTTP Basic, ADMIN role — see SecurityConfig).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SupportController {

    private final SupportTicketRepository tickets;
    private final TechnicianRepository technicians;
    private final FieldOpsService fieldOps;
    private final AuditService audit;

    // --- Public: customers open tickets ---

    public record CreateTicketRequest(
            @NotBlank String customerName,
            @NotBlank String phoneNumber,
            @NotBlank String subject,
            @NotBlank String message,
            SupportTicket.Priority priority) {
    }

    @PostMapping("/api/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SupportTicket create(@Valid @RequestBody CreateTicketRequest request) {
        SupportTicket ticket = SupportTicket.builder()
                .customerName(request.customerName())
                .phoneNumber(request.phoneNumber())
                .subject(request.subject())
                .priority(request.priority() != null ? request.priority() : SupportTicket.Priority.MEDIUM)
                .build();
        ticket.getMessages().add(TicketMessage.builder()
                .ticket(ticket)
                .fromAdmin(false)
                .body(request.message())
                .build());
        return tickets.save(ticket);
    }

    // --- Admin: manage tickets ---

    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @GetMapping("/api/admin/tickets")
    public List<SupportTicket> all() {
        return tickets.findTop100ByOrderByUpdatedAtDesc();
    }

    public record AdminTicketRequest(
            @NotBlank String customerName,
            @NotBlank String phoneNumber,
            @NotBlank String subject,
            @NotBlank String message,
            SupportTicket.Priority priority,
            Set<Long> assigneeIds) {
    }

    /**
     * A staff member raising a ticket on a customer's behalf — a walk-in
     * complaint, or a fault the team spotted before the customer called.
     */
    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @PostMapping("/api/admin/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SupportTicket createAsAdmin(@Valid @RequestBody AdminTicketRequest request, Principal principal) {
        SupportTicket ticket = SupportTicket.builder()
                .customerName(request.customerName())
                .phoneNumber(request.phoneNumber())
                .subject(request.subject())
                .priority(request.priority() != null ? request.priority() : SupportTicket.Priority.MEDIUM)
                .createdBy(principal.getName())
                .build();
        ticket.getMessages().add(TicketMessage.builder()
                .ticket(ticket)
                .fromAdmin(true)
                .body(request.message())
                .build());
        applyAssignees(ticket, request.assigneeIds());
        // A ticket someone is already on is not sitting untriaged; the assign
        // endpoint does the same, so the two paths agree.
        if (!ticket.getAssigneeIds().isEmpty()) {
            ticket.setStatus(SupportTicket.Status.IN_PROGRESS);
            ticket.setWorkStartedAt(Instant.now());
        }
        SupportTicket saved = tickets.save(ticket);
        notifyAssignees(saved, saved.getAssigneeIds());
        audit.record(principal, "ticket.create", "Raised ticket \"" + saved.getSubject()
                + "\" for " + saved.getCustomerName());
        return saved;
    }

    public record AssignRequest(Set<Long> assigneeIds) {
    }

    /** Replaces the assignee list; an empty set puts the ticket back in the pool. */
    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @PatchMapping("/api/admin/tickets/{id}/assignees")
    @Transactional
    public SupportTicket assign(@PathVariable Long id, @RequestBody AssignRequest request, Principal principal) {
        SupportTicket ticket = get(id);
        Set<Long> before = new LinkedHashSet<>(ticket.getAssigneeIds());
        applyAssignees(ticket, request.assigneeIds());

        Set<Long> added = new LinkedHashSet<>(ticket.getAssigneeIds());
        added.removeAll(before);
        notifyAssignees(ticket, added);

        // Picking up an unassigned ticket means work has started on it.
        if (!ticket.getAssigneeIds().isEmpty() && ticket.getStatus() == SupportTicket.Status.OPEN) {
            ticket.setStatus(SupportTicket.Status.IN_PROGRESS);
        }
        // Only ever set once: reassigning a job must not restart its clock
        // and make something that has dragged on for a week look fresh.
        if (!ticket.getAssigneeIds().isEmpty() && ticket.getWorkStartedAt() == null) {
            ticket.setWorkStartedAt(Instant.now());
        }
        audit.record(principal, "ticket.assign", ticket.getAssigneeIds().isEmpty()
                ? "Unassigned ticket " + id
                : "Assigned ticket " + id + " to " + describe(ticket.getAssigneeIds()));
        return tickets.save(ticket);
    }

    /** Only real, active technicians can hold a ticket. */
    private void applyAssignees(SupportTicket ticket, Set<Long> requested) {
        Set<Long> valid = new LinkedHashSet<>();
        if (requested != null) {
            for (Long technicianId : requested) {
                Technician tech = technicians.findById(technicianId).orElseThrow(
                        () -> new IllegalArgumentException("Unknown technician: " + technicianId));
                if (!tech.isActive()) {
                    throw new IllegalArgumentException(tech.getFullName() + " is disabled and cannot take tickets");
                }
                valid.add(technicianId);
            }
        }
        ticket.getAssigneeIds().clear();
        ticket.getAssigneeIds().addAll(valid);
    }

    /** Best-effort heads-up; a failed message must never block the assignment. */
    private void notifyAssignees(SupportTicket ticket, Set<Long> technicianIds) {
        try {
            fieldOps.notifyAssignment(ticket, technicianIds);
        } catch (Exception e) {
            log.warn("Could not notify assignees of ticket {}: {}", ticket.getId(), e.getMessage());
        }
    }

    private String describe(Set<Long> technicianIds) {
        return technicianIds.stream()
                .map(id -> technicians.findById(id).map(Technician::getFullName).orElse("#" + id))
                .collect(Collectors.joining(", "));
    }

    // --- Technician: my jobs ---

    /** Tickets assigned to whoever is signed in on the technician app. */
    @GetMapping("/api/tech/tickets")
    @Transactional(readOnly = true)
    public List<SupportTicket> myTickets(Principal principal) {
        Long me = technicians.findByUsername(principal.getName())
                .map(Technician::getId)
                .orElse(null);
        if (me == null) {
            return List.of(); // the shared admin login has no technician row
        }
        return tickets.findTop100ByOrderByUpdatedAtDesc().stream()
                .filter(t -> t.getAssigneeIds().contains(me))
                .toList();
    }

    public record TechStatusRequest(@NotNull SupportTicket.Status status, String note) {
    }

    /**
     * Lets the technician close the job they are on. Without this the office
     * had to close every ticket by hand, so "in progress" meant only that
     * somebody had been assigned — not that anyone was still working.
     *
     * <p>A technician may only touch a ticket they are actually on, and may
     * not reopen a resolved one; that is the office's call.
     */
    @PatchMapping("/api/tech/tickets/{id}/status")
    @Transactional
    public SupportTicket updateMyTicket(@PathVariable Long id,
                                        @Valid @RequestBody TechStatusRequest request,
                                        Principal principal) {
        Technician me = technicians.findByUsername(principal.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Only a technician account can update a job this way"));
        SupportTicket ticket = get(id);
        if (!ticket.getAssigneeIds().contains(me.getId())) {
            throw new IllegalStateException("That job is not assigned to you");
        }
        if (ticket.getStatus() == SupportTicket.Status.RESOLVED) {
            throw new IllegalStateException(
                    "That job is already closed. Ask the office to reopen it if it is not really done.");
        }

        if (request.note() != null && !request.note().isBlank()) {
            ticket.getMessages().add(TicketMessage.builder()
                    .ticket(ticket)
                    .fromAdmin(true)
                    .body(me.getFullName() + ": " + request.note().trim())
                    .build());
        }

        ticket.setStatus(request.status());
        if (request.status() == SupportTicket.Status.RESOLVED) {
            ticket.setResolvedAt(Instant.now());
            ticket.setResolvedBy(me.getFullName());
        }
        return tickets.save(ticket);
    }

    private SupportTicket get(Long id) {
        return tickets.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ticket: " + id));
    }

    /**
     * How the support desk is doing: load by status and priority, how long
     * customers wait for a first reply, how long tickets take to resolve,
     * and which subjects come up most. Times are measured from the stored
     * messages rather than tracked separately, so they cannot drift.
     */
    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @GetMapping("/api/admin/tickets/analytics")
    @Transactional(readOnly = true)
    public Map<String, Object> analytics() {
        List<SupportTicket> all = tickets.findAll();

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (SupportTicket.Status status : SupportTicket.Status.values()) {
            byStatus.put(status.name(), 0);
        }
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        for (SupportTicket.Priority priority : SupportTicket.Priority.values()) {
            byPriority.put(priority.name(), 0);
        }

        List<Long> firstReplyMinutes = new ArrayList<>();
        List<Long> resolveMinutes = new ArrayList<>();
        Map<String, Integer> subjects = new HashMap<>();
        Map<String, Integer> perDay = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = 13; i >= 0; i--) {
            perDay.put(today.minusDays(i).toString(), 0);
        }
        int openedLast7 = 0;
        int awaitingReply = 0;

        for (SupportTicket ticket : all) {
            byStatus.merge(ticket.getStatus().name(), 1, Integer::sum);
            byPriority.merge(ticket.getPriority().name(), 1, Integer::sum);

            String subject = ticket.getSubject() == null ? "(none)" : ticket.getSubject().trim();
            subjects.merge(subject, 1, Integer::sum);

            LocalDate opened = ticket.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
            perDay.computeIfPresent(opened.toString(), (k, v) -> v + 1);
            if (!opened.isBefore(today.minusDays(6))) {
                openedLast7++;
            }

            TicketMessage firstStaffReply = ticket.getMessages().stream()
                    .filter(TicketMessage::isFromAdmin)
                    .min(Comparator.comparing(TicketMessage::getCreatedAt))
                    .orElse(null);
            if (firstStaffReply != null) {
                firstReplyMinutes.add(Duration.between(ticket.getCreatedAt(),
                        firstStaffReply.getCreatedAt()).toMinutes());
            } else if (ticket.getStatus() != SupportTicket.Status.RESOLVED) {
                awaitingReply++;
            }

            if (ticket.getStatus() == SupportTicket.Status.RESOLVED) {
                resolveMinutes.add(Duration.between(ticket.getCreatedAt(), ticket.getUpdatedAt()).toMinutes());
            }
        }

        List<Map<String, Object>> topSubjects = subjects.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(6)
                .map(e -> Map.<String, Object>of("subject", e.getKey(), "count", e.getValue()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("byStatus", byStatus);
        out.put("byPriority", byPriority);
        out.put("openedLast7Days", openedLast7);
        out.put("awaitingFirstReply", awaitingReply);
        out.put("avgFirstReplyMinutes", average(firstReplyMinutes));
        out.put("medianFirstReplyMinutes", median(firstReplyMinutes));
        out.put("avgResolveMinutes", average(resolveMinutes));
        out.put("resolvedCount", resolveMinutes.size());
        out.put("topSubjects", topSubjects);
        out.put("perDay", perDay.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey(), "count", e.getValue()))
                .toList());
        return out;
    }

    /** Null rather than zero when there is nothing to average, so the UI can say "no data". */
    private static Long average(List<Long> values) {
        return values.isEmpty() ? null
                : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    public record ReplyRequest(@NotBlank String body) {
    }

    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @PostMapping("/api/admin/tickets/{id}/reply")
    @Transactional
    public SupportTicket reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest request) {
        SupportTicket ticket = tickets.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ticket: " + id));
        ticket.getMessages().add(TicketMessage.builder()
                .ticket(ticket)
                .fromAdmin(true)
                .body(request.body())
                .build());
        if (ticket.getStatus() == SupportTicket.Status.OPEN) {
            ticket.setStatus(SupportTicket.Status.IN_PROGRESS);
        }
        return tickets.save(ticket);
    }

    public record StatusRequest(@NotNull SupportTicket.Status status) {
    }

    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @PatchMapping("/api/admin/tickets/{id}/status")
    @Transactional
    public SupportTicket setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request,
                                   Principal principal) {
        SupportTicket ticket = tickets.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ticket: " + id));
        ticket.setStatus(request.status());
        if (request.status() == SupportTicket.Status.RESOLVED && ticket.getResolvedAt() == null) {
            ticket.setResolvedAt(Instant.now());
            ticket.setResolvedBy(principal == null ? "office" : principal.getName());
        }
        return tickets.save(ticket);
    }
}
