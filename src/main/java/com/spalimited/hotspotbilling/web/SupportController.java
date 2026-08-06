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

import java.util.List;

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
