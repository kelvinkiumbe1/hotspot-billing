package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CallAgent;
import com.spalimited.hotspotbilling.domain.CallRecord;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.CallRecordRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import com.spalimited.hotspotbilling.service.calls.CallCentreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A technician ringing a customer from the business number.
 *
 * <p>Until now the field app offered a {@code tel:} link, which dials from the
 * technician's own handset and puts their personal number on the customer's
 * screen — the exact thing the virtual number was bought to prevent, left in
 * place for the people who make the most calls. It also meant those calls were
 * invisible: no record, no recording, no way for an operator to hear what was
 * said when a job goes wrong.
 *
 * <p>Three rules, all enforced here rather than in the app:
 *
 * <ul>
 *   <li>A technician can only ring a customer on a job assigned to <em>them</em>.
 *       Otherwise the field app becomes a directory of every customer's number.
 *   <li>The number is read from the job, never taken from the request. A stale
 *       screen cannot dial a number the customer has since changed, and a
 *       tampered request cannot dial anywhere at all.
 *   <li>They are put on the outbound rota only. Placing a call must not sign
 *       somebody up to answer the support line from the top of a ladder.
 * </ul>
 */
@RestController
@RequestMapping("/api/tech/calls")
@RequiredArgsConstructor
@Slf4j
public class TechCallController {

    private final CallCentreService callCentre;
    private final TechnicianRepository technicians;
    private final SupportTicketRepository tickets;
    private final CallRecordRepository calls;

    public record DialRequest(@NotNull Long ticketId) {
    }

    private Technician me(Principal principal) {
        return technicians.findByUsername(principal.getName())
                .filter(Technician::isActive)
                .orElseThrow(() -> new IllegalStateException("That login is not an active technician"));
    }

    /**
     * Whether the call centre can place a call at all, and who the caller is.
     *
     * <p>The field app asks before drawing the button: an operator who has not
     * set the call centre up should see the old behaviour rather than a button
     * that fails when it is pressed on somebody's roof.
     */
    @GetMapping("/status")
    public Map<String, Object> status(Principal principal) {
        Technician tech = me(principal);
        Map<String, Object> out = new LinkedHashMap<>();
        String blocked = callCentre.whyNotUsable();
        out.put("available", blocked == null
                && tech.getPhoneNumber() != null && !tech.getPhoneNumber().isBlank());
        out.put("reason", blocked != null ? blocked
                : (tech.getPhoneNumber() == null || tech.getPhoneNumber().isBlank()
                        ? "Your phone number is not on file — ask the office to add it."
                        : null));
        return out;
    }

    /**
     * Rings the technician first, then bridges them to the customer.
     *
     * <p>That order is not incidental: the customer's phone only rings once a
     * human is already on the line, so nobody picks up to silence. It also means
     * the technician's own handset is used without their number being shown.
     */
    @PostMapping("/dial")
    public Map<String, Object> dial(@Valid @RequestBody DialRequest request, Principal principal) {
        Technician tech = me(principal);
        SupportTicket job = tickets.findById(request.ticketId())
                .orElseThrow(() -> new IllegalArgumentException("No such job"));

        // Assigned to them, not merely open. Without this the field app is a
        // list of every customer's phone number for anybody with a technician
        // login.
        if (!job.getAssigneeIds().contains(tech.getId())) {
            log.warn("Technician {} tried to call on job {}, which is not theirs",
                    tech.getId(), job.getId());
            throw new IllegalArgumentException("That job is not assigned to you");
        }

        // From the record, never the request. A stale screen cannot dial a
        // number the customer has since changed, and a tampered request cannot
        // dial anywhere at all.
        String number = job.getPhoneNumber();
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("That job has no phone number on it");
        }

        CallAgent agent = callCentre.agentForTechnician(tech);
        CallCentreService.Dialled result =
                callCentre.dial(agent.getId(), number, null, job.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("message", result.ok()
                ? "Your phone is ringing — answer it and we will connect the customer."
                : result.message());
        return out;
    }

    /** The technician's own recent calls, so they can see what connected. */
    @GetMapping("/mine")
    public List<Map<String, Object>> mine(Principal principal) {
        Technician tech = me(principal);
        CallAgent agent = callCentre.agentForTechnician(tech);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CallRecord c : calls.findTop50ByAgentIdOrderByStartedAtDesc(agent.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("number", c.getDestinationNumber());
            row.put("startedAt", c.getStartedAt());
            row.put("status", c.getStatus());
            row.put("durationSeconds", c.getDurationSeconds());
            row.put("ticketId", c.getTicketId());
            // Deliberately not the recording: a technician hearing their own call
            // back is fine, a technician browsing the office's recordings is not,
            // and the two are one query apart. Recordings stay in the admin.
            out.add(row);
        }
        return out;
    }
}
