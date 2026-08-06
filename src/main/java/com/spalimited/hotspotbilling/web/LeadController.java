package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Lead;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.LeadRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prospective customers, and converting them into subscribers. */
@RestController
@RequestMapping("/api/admin/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadRepository leads;
    private final SubscriptionService subscriptionService;
    private final AuditService audit;

    @GetMapping
    public List<Lead> all() {
        return leads.findAllByOrderByCreatedAtDesc();
    }

    /** Counts per status, for the pipeline strip. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Lead.Status status : Lead.Status.values()) {
            out.put(status.name(), leads.findByStatus(status).size());
        }
        out.put("total", leads.count());
        return out;
    }

    public record LeadRequest(
            @NotBlank String fullName,
            @Pattern(regexp = "254\\d{9}", message = "Phone must be in 2547XXXXXXXX format") String phoneNumber,
            String location,
            String interestedIn,
            BigDecimal quotedFee,
            Lead.Source source,
            String notes) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Lead create(@Valid @RequestBody LeadRequest request, Principal principal) {
        Lead lead = leads.save(Lead.builder()
                .fullName(request.fullName())
                .phoneNumber(request.phoneNumber())
                .location(request.location())
                .interestedIn(request.interestedIn())
                .quotedFee(request.quotedFee())
                .source(request.source() != null ? request.source() : Lead.Source.WALK_IN)
                .notes(request.notes())
                .createdBy(principal.getName())
                .build());
        audit.record(principal, "lead.create", "Added lead " + lead.getFullName());
        return lead;
    }

    public record StatusRequest(@NotNull Lead.Status status, String notes) {
    }

    @PatchMapping("/{id}/status")
    public Lead setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request, Principal principal) {
        Lead lead = get(id);
        lead.setStatus(request.status());
        if (request.notes() != null && !request.notes().isBlank()) {
            lead.setNotes(request.notes());
        }
        audit.record(principal, "lead.status", lead.getFullName() + " moved to " + request.status());
        return leads.save(lead);
    }

    public record ConvertRequest(
            @NotBlank String pppoeUsername,
            @NotBlank @Size(min = 6) String pppoePassword,
            String bandwidth,
            @NotNull @Min(1) BigDecimal monthlyFee,
            @Min(0) @Max(12) Integer initialMonths,
            String initialMethod) {
    }

    /** Signs the lead up as a PPPoE subscriber and marks it converted. */
    @PostMapping("/{id}/convert")
    public Subscriber convert(@PathVariable Long id, @Valid @RequestBody ConvertRequest request, Principal principal) {
        Lead lead = get(id);
        if (lead.getSubscriberId() != null) {
            throw new IllegalStateException("This lead has already been converted");
        }
        Subscriber sub = subscriptionService.create(
                lead.getFullName(),
                lead.getPhoneNumber(),
                request.pppoeUsername(),
                request.pppoePassword(),
                request.bandwidth(),
                request.monthlyFee(),
                request.initialMonths() != null ? request.initialMonths() : 1,
                "MPESA".equalsIgnoreCase(request.initialMethod())
                        ? SubscriptionPayment.Method.MPESA : SubscriptionPayment.Method.CASH,
                principal.getName());
        lead.setStatus(Lead.Status.CONVERTED);
        lead.setSubscriberId(sub.getId());
        leads.save(lead);
        audit.record(principal, "lead.convert", lead.getFullName() + " converted to subscriber " + sub.getPppoeUsername());
        return sub;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Principal principal) {
        leads.findById(id).ifPresent(lead -> {
            audit.record(principal, "lead.delete", "Removed lead " + lead.getFullName());
            leads.delete(lead);
        });
    }

    private Lead get(Long id) {
        return leads.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown lead: " + id));
    }
}
