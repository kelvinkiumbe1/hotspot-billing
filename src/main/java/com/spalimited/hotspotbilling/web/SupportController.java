package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.TicketMessage;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Support tickets. Customers open tickets through the public
 * POST /api/tickets endpoint; everything under /api/admin/tickets is for
 * staff (HTTP Basic, ADMIN role — see SecurityConfig).
 */
@RestController
@RequiredArgsConstructor
public class SupportController {

    private final SupportTicketRepository tickets;

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

    @GetMapping("/api/admin/tickets")
    public List<SupportTicket> all() {
        return tickets.findTop100ByOrderByUpdatedAtDesc();
    }

    /**
     * How the support desk is doing: load by status and priority, how long
     * customers wait for a first reply, how long tickets take to resolve,
     * and which subjects come up most. Times are measured from the stored
     * messages rather than tracked separately, so they cannot drift.
     */
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

    @PatchMapping("/api/admin/tickets/{id}/status")
    @Transactional
    public SupportTicket setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        SupportTicket ticket = tickets.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ticket: " + id));
        ticket.setStatus(request.status());
        return tickets.save(ticket);
    }
}
