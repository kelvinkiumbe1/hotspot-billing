package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.LedgerAdjustment;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Customer statements, balances and manual adjustments. */
@RestController
@RequestMapping("/api/admin/ledger")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FINANCE')")
public class LedgerController {

    private final LedgerService ledgerService;
    private final AuditService audit;

    /** Everyone who owes money or sits in credit, worst arrears first. */
    @GetMapping("/outstanding")
    public List<Map<String, Object>> outstanding() {
        return ledgerService.outstanding();
    }

    /**
     * One customer's statement. The running balance always accumulates from
     * the beginning, so a windowed statement still opens at the right figure.
     */
    @GetMapping("/{subscriberId}")
    public Map<String, Object> statement(
            @PathVariable Long subscriberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<LedgerService.Entry> entries = ledgerService.statement(subscriberId, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", ledgerService.summary(subscriberId));
        out.put("from", from);
        out.put("to", to);
        out.put("entries", entries.stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.date());
            row.put("type", e.type());
            row.put("reference", e.reference());
            row.put("description", e.description());
            row.put("debit", e.debit());
            row.put("credit", e.credit());
            row.put("balance", e.balance());
            return row;
        }).toList());
        // Opening balance for the window: the first line's balance less its
        // own movement, so the statement shows where the period started.
        out.put("openingBalance", entries.isEmpty() ? BigDecimal.ZERO
                : entries.get(0).balance().subtract(entries.get(0).signedAmount()));
        return out;
    }

    public record AdjustRequest(
            @NotNull LedgerAdjustment.Kind kind,
            @NotNull BigDecimal amount,
            @NotBlank String reason,
            LocalDate appliedOn) {
    }

    @PostMapping("/{subscriberId}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> adjust(@PathVariable Long subscriberId,
                                      @Valid @RequestBody AdjustRequest request,
                                      Principal principal) {
        LedgerAdjustment saved = ledgerService.adjust(subscriberId, request.kind(), request.amount(),
                request.reason(), request.appliedOn(), principal.getName());
        audit.record(principal, "ledger.adjust", saved.getKind() + " of "
                + saved.getAmount() + " for " + saved.getSubscriber().getPppoeUsername()
                + " — " + saved.getReason());
        return Map.of("id", saved.getId(), "kind", saved.getKind(), "amount", saved.getAmount(),
                "balance", ledgerService.balance(subscriberId));
    }

    @DeleteMapping("/adjustments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAdjustment(@PathVariable Long id, Principal principal) {
        audit.record(principal, "ledger.adjust.delete", "Reversed adjustment " + id);
        ledgerService.removeAdjustment(id);
    }
}
