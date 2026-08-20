package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.BankImport;
import com.spalimited.hotspotbilling.domain.BankTransaction;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.BankImportRepository;
import com.spalimited.hotspotbilling.repository.BankTransactionRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.BankStatementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bank statements: uploading them, and clearing the queue they produce.
 *
 * <p>The CSV is parsed in the browser, like the subscriber import already is.
 * That is not laziness -- bank CSVs disagree about column names, date formats and
 * whether debits are negative or a separate column, so the operator has to see
 * the parse and correct the mapping before anything is stored. Doing that on the
 * server would mean uploading, failing, and guessing again.
 */
@RestController
@RequestMapping("/api/admin/bank")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FINANCE')")
public class BankStatementController {

    private final BankStatementService bankStatements;
    private final BankImportRepository imports;
    private final BankTransactionRepository transactions;
    private final SubscriberRepository subscribers;

    public record RowIn(LocalDate valueDate, @Size(max = 1000) String narration,
                        @Size(max = 120) String bankReference, BigDecimal amount) {
    }

    public record ImportRequest(String filename, String bankName,
                                @NotEmpty List<@Valid RowIn> rows) {
    }

    @PostMapping("/import")
    public Map<String, Object> importStatement(@Valid @RequestBody ImportRequest request,
                                              Principal principal) {
        List<BankStatementService.Row> rows = new ArrayList<>();
        for (RowIn row : request.rows()) {
            rows.add(new BankStatementService.Row(
                    row.valueDate(), row.narration(), row.bankReference(), row.amount()));
        }
        return bankStatements.ingest(request.filename(), request.bankName(), rows,
                principal == null ? "admin" : principal.getName());
    }

    @GetMapping("/imports")
    public Map<String, Object> recentImports() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BankImport b : imports.findAllByOrderByUploadedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("filename", b.getFilename());
            row.put("bankName", b.getBankName());
            row.put("uploadedAt", b.getUploadedAt());
            row.put("uploadedBy", b.getUploadedBy());
            row.put("credits", b.getCreditCount());
            row.put("duplicates", b.getDuplicateCount());
            row.put("matched", b.getMatchedCount());
            row.put("applied", b.getAppliedCount());
            rows.add(row);
        }
        return Map.of("imports", rows,
                "waiting", transactions.countByStatus(BankTransaction.Status.UNMATCHED)
                        + transactions.countByStatus(BankTransaction.Status.MATCHED));
    }

    /** Everything still waiting on a person, oldest first. */
    @GetMapping("/queue")
    public Map<String, Object> queue() {
        return Map.of("transactions", render(transactions.findByStatusInOrderByValueDateAsc(
                List.of(BankTransaction.Status.UNMATCHED, BankTransaction.Status.MATCHED))));
    }

    @GetMapping("/import/{id}/transactions")
    public Map<String, Object> ofImport(@PathVariable Long id) {
        return Map.of("transactions", render(transactions.findByImportIdOrderByValueDateAsc(id)));
    }

    public record ApplyRequest(Long subscriberId) {
    }

    @PostMapping("/transaction/{id}/apply")
    public Map<String, Object> apply(@PathVariable Long id,
                                     @RequestBody(required = false) ApplyRequest request,
                                     Principal principal) {
        return bankStatements.apply(id, request == null ? null : request.subscriberId(),
                principal == null ? "admin" : principal.getName());
    }

    @PostMapping("/transaction/{id}/ignore")
    public Map<String, Object> ignore(@PathVariable Long id, Principal principal) {
        return bankStatements.ignore(id, principal == null ? "admin" : principal.getName());
    }

    /**
     * A transaction as the queue screen needs it.
     *
     * <p>The suggested customer is expanded into a name and what they pay, and
     * the months the amount would buy is worked out here rather than in the
     * browser -- the same arithmetic that will run when it is applied, so the
     * number somebody agrees to is the number they get.
     */
    private List<Map<String, Object>> render(List<BankTransaction> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BankTransaction t : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("valueDate", t.getValueDate());
            row.put("narration", t.getNarration());
            row.put("bankReference", t.getBankReference());
            row.put("amount", t.getAmount());
            row.put("status", t.getStatus());
            row.put("matchReason", t.getMatchReason());
            row.put("decidedAt", t.getDecidedAt());
            row.put("decidedBy", t.getDecidedBy());
            if (t.getSubscriberId() != null) {
                Subscriber sub = subscribers.findById(t.getSubscriberId()).orElse(null);
                if (sub != null) {
                    row.put("subscriberId", sub.getId());
                    row.put("subscriberName", sub.getFullName());
                    row.put("pppoeUsername", sub.getPppoeUsername());
                    row.put("monthlyFee", sub.getMonthlyFee());
                    row.put("months", bankStatements.monthsFor(sub, t.getAmount()));
                }
            }
            out.add(row);
        }
        return out;
    }
}
