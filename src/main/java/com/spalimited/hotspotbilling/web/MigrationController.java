package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.MigrationBatch;
import com.spalimited.hotspotbilling.domain.MigrationRow;
import com.spalimited.hotspotbilling.service.migration.MigrationImportService;
import com.spalimited.hotspotbilling.service.migration.MigrationSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Moving in.
 *
 * <p>The CSV is parsed in the browser, as the subscriber and bank-statement
 * imports already are, so what arrives here is the rows rather than a file.
 * Every step before {@code promote} is read-only as far as the ISP's live
 * business is concerned.
 */
@RestController
@RequestMapping("/api/admin/migration")
@RequiredArgsConstructor
// The whole customer book, with logins and prices, passes through here. That is
// an owner-level thing to be doing, not a support-desk one.
@PreAuthorize("hasAuthority('SETTINGS')")
public class MigrationController {

    private final MigrationImportService migration;

    /** What an operator can bring a book across from. */
    @GetMapping("/sources")
    public List<Map<String, String>> sources() {
        List<Map<String, String>> out = new ArrayList<>();
        for (MigrationSource source : MigrationSource.values()) {
            out.add(Map.of("value", source.name(), "label", switch (source) {
                case SPLYNX -> "Splynx";
                case UISP -> "UISP / UCRM (Ubiquiti)";
                case RADIUS_MANAGER -> "Radius Manager";
                case GENERIC -> "Something else (CSV)";
            }));
        }
        return out;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MigrationBatch batch : migration.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.getId());
            row.put("source", batch.getSource());
            row.put("label", batch.getLabel());
            row.put("status", batch.getStatus());
            row.put("rowCount", batch.getRowCount());
            row.put("createdAt", batch.getCreatedAt());
            row.put("createdBy", batch.getCreatedBy());
            row.put("promotedAt", batch.getPromotedAt());
            out.add(row);
        }
        return out;
    }

    public record StageRequest(MigrationSource source, String label,
                               MigrationImportService.DateOrder dateOrder,
                               @NotEmpty(message = "There were no rows in that file")
                               List<Map<String, String>> rows) {
    }

    /** Stages an upload. Creates no customers and touches no router. */
    @PostMapping
    public MigrationImportService.Staged stage(@Valid @RequestBody StageRequest body,
                                              Principal principal) {
        return migration.stage(body.source(), body.label(), body.dateOrder(), body.rows(),
                principal != null ? principal.getName() : "system");
    }

    /** What would happen, in counts and money. */
    @GetMapping("/{id}")
    public Map<String, Object> plan(@PathVariable Long id) {
        return migration.plan(id);
    }

    /** What we would charge next month against what they charge now. */
    @GetMapping("/{id}/compare")
    public Map<String, Object> compare(@PathVariable Long id) {
        return migration.compare(id);
    }

    /** The staged rows themselves, so an operator can see the verdicts one by one. */
    @GetMapping("/{id}/rows")
    public List<Map<String, Object>> rows(@PathVariable Long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MigrationRow row : migration.rowsOf(id)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("externalId", row.getExternalId());
            item.put("fullName", row.getFullName());
            item.put("phoneNumber", row.getPhoneNumber());
            item.put("pppoeUsername", row.getPppoeUsername());
            item.put("planName", row.getPlanName());
            item.put("monthlyPrice", row.getMonthlyPrice());
            item.put("staticIp", row.getStaticIp());
            item.put("paidUntil", row.getPaidUntil());
            item.put("balance", row.getBalance());
            item.put("verdict", row.getVerdict());
            item.put("verdictNote", row.getVerdictNote());
            item.put("matchedPlanId", row.getMatchedPlanId());
            item.put("subscriberId", row.getSubscriberId());
            // Deliberately not the password, even to an owner: there is no reason
            // to render three thousand PPPoE secrets into a browser tab.
            out.add(item);
        }
        return out;
    }

    /** Creates the customers. The only step here that changes anything. */
    @PostMapping("/{id}/promote")
    public MigrationImportService.Promoted promote(@PathVariable Long id, Principal principal) {
        return migration.promote(id, principal != null ? principal.getName() : "system");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> discard(@PathVariable Long id, Principal principal) {
        migration.discard(id, principal != null ? principal.getName() : "system");
        return Map.of("ok", true, "message", "That import was thrown away.");
    }
}
