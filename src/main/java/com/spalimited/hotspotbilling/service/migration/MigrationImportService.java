package com.spalimited.hotspotbilling.service.migration;

import com.spalimited.hotspotbilling.domain.MigrationBatch;
import com.spalimited.hotspotbilling.domain.MigrationRow;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.MigrationBatchRepository;
import com.spalimited.hotspotbilling.repository.MigrationRowRepository;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bringing an ISP's book across from whatever they are on now.
 *
 * <p>The reason this exists is commercial. An ISP with three thousand
 * subscribers will not retype them, and will not risk a cutover that loses a
 * month's invoicing — so the strongest product in the world loses to the one
 * they are already on. Removing that risk is the feature.
 *
 * <p>The shape is deliberate: an upload is <em>staged</em>, never applied. Rows
 * sit in their own table with a verdict each, the operator is shown what would
 * happen and what Zidi would have charged next to what the old system did, and
 * only then are real customers created. Nothing here can move money or touch a
 * router, which is what makes it safe to run against a live book on a Tuesday
 * afternoon.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationImportService {

    /** Above this a single upload is refused: it is a paste accident, not a book. */
    private static final int MAX_ROWS = 20_000;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final MigrationBatchRepository batches;
    private final MigrationRowRepository rows;
    private final SubscriberRepository subscribers;
    private final PlanRepository plans;
    private final PhoneNumbers phoneNumbers;
    private final AuditService audit;
    private final MigrationRowPromoter promoter;

    /**
     * How to read a date like {@code 03/04/2026}.
     *
     * <p>AUTO accepts it only when it cannot be misread — when one number is
     * above twelve. A wrong expiry is the single most expensive mistake an import
     * can make: too early and paying customers are cut off, too late and a month
     * is given away. Guessing is worse than leaving it empty and saying so.
     */
    public enum DateOrder { AUTO, DMY, MDY }

    /** What an upload turned into, without having changed anything yet. */
    public record Staged(Long batchId, int total, long ready, long collisions,
                         long incomplete, List<String> warnings) {
    }

    /**
     * Stages an upload.
     *
     * <p>The CSV is parsed in the browser, as the subscriber and bank imports
     * already are, so what arrives here is a list of heading-to-value maps.
     */
    @Transactional
    public Staged stage(MigrationSource source, String label, DateOrder dateOrder,
                        List<Map<String, String>> incoming, String by) {
        if (incoming == null || incoming.isEmpty()) {
            throw new IllegalArgumentException("There were no rows in that file");
        }
        if (incoming.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "That is " + incoming.size() + " rows — more than " + MAX_ROWS
                            + ". Split the export and bring it across in parts.");
        }
        MigrationSource src = source == null ? MigrationSource.GENERIC : source;
        DateOrder order = dateOrder == null ? DateOrder.AUTO : dateOrder;

        MigrationBatch batch = batches.save(MigrationBatch.builder()
                .source(src).label(label).status(MigrationBatch.Status.STAGED)
                .rowCount(incoming.size()).createdAt(Instant.now()).createdBy(by)
                .build());

        List<Plan> allPlans = plans.findAll();
        // Read once: on a three thousand row book, asking the database per row
        // turns a ten second import into several minutes.
        List<Subscriber> existing = subscribers.findAll();
        List<String> warnings = new ArrayList<>();
        int unparsedDates = 0;
        int unmatchedPlans = 0;

        // Logins already claimed by an earlier row in this same file. A bad export
        // repeats a customer, and comparing only against the existing book would
        // pass both rows as new -- the second then fails at promotion, which works
        // but tells the operator nothing while they are still deciding.
        java.util.Set<String> claimedInThisFile = new java.util.HashSet<>();

        for (Map<String, String> raw : incoming) {
            MigrationRow row = read(src, order, raw);
            resolvePlan(row, allPlans);
            judge(row, existing);
            if (row.getVerdict() == MigrationRow.Verdict.NEW && row.getPppoeUsername() != null
                    && !claimedInThisFile.add(row.getPppoeUsername().toLowerCase(Locale.ROOT))) {
                row.setVerdict(MigrationRow.Verdict.COLLISION);
                row.setVerdictNote("The login '" + row.getPppoeUsername() + "' appears more than "
                        + "once in this file. The first one will be brought across.");
            }
            if (row.getPaidUntil() == null && raw.values().stream().anyMatch(v -> v != null && !v.isBlank())
                    && src.read(raw, MigrationSource.Field.PAID_UNTIL) != null) {
                unparsedDates++;
            }
            if (row.getPlanName() != null && row.getMatchedPlanId() == null) {
                unmatchedPlans++;
            }
            row.setBatchId(batch.getId());
            rows.save(row);
        }

        if (unparsedDates > 0) {
            warnings.add(unparsedDates + " row(s) had an expiry date that could not be read "
                    + "without guessing. Say which order the dates are in and upload again, "
                    + "or those customers will come across with no paid-up date.");
        }
        if (unmatchedPlans > 0) {
            warnings.add(unmatchedPlans + " row(s) name a package that does not exist here yet. "
                    + "Create the packages first, or they will come across on their old price "
                    + "with no speed set.");
        }

        long ready = rows.countByBatchIdAndVerdict(batch.getId(), MigrationRow.Verdict.NEW);
        long collisions = rows.countByBatchIdAndVerdict(batch.getId(), MigrationRow.Verdict.COLLISION);
        long incomplete = rows.countByBatchIdAndVerdict(batch.getId(), MigrationRow.Verdict.INCOMPLETE);
        audit.record(by, "migration.stage",
                "Staged " + incoming.size() + " row(s) from " + src + " (batch " + batch.getId() + ")");
        log.info("Staged migration batch {} from {}: {} ready, {} collisions, {} incomplete",
                batch.getId(), src, ready, collisions, incomplete);
        return new Staged(batch.getId(), incoming.size(), ready, collisions, incomplete, warnings);
    }

    // --- reading one row ---

    private MigrationRow read(MigrationSource src, DateOrder order, Map<String, String> raw) {
        String phone = src.read(raw, MigrationSource.Field.PHONE);
        String normalised = phone == null ? null : phoneNumbers.normalise(phone);
        // Every value is cut to the width of the column it lands in. Without this
        // one three-hundred-character company name in a three thousand row export
        // failed the whole upload with an opaque 500 and no clue which row did it.
        return MigrationRow.builder()
                .externalId(cap(src.read(raw, MigrationSource.Field.EXTERNAL_ID), 120))
                .fullName(cap(src.readName(raw), 255))
                .phoneNumber(cap(normalised != null ? normalised : phone, 64))
                .pppoeUsername(cap(trimToNull(src.read(raw, MigrationSource.Field.USERNAME)), 120))
                .pppoePassword(cap(trimToNull(src.read(raw, MigrationSource.Field.PASSWORD)), 120))
                .planName(cap(src.read(raw, MigrationSource.Field.PLAN), 200))
                .monthlyPrice(money(src.read(raw, MigrationSource.Field.PRICE)))
                .staticIp(cap(firstAddress(src.read(raw, MigrationSource.Field.STATIC_IP)), 64))
                .externalStatus(cap(src.read(raw, MigrationSource.Field.STATUS), 64))
                .balance(money(src.read(raw, MigrationSource.Field.BALANCE)))
                .paidUntil(date(src.read(raw, MigrationSource.Field.PAID_UNTIL), order))
                .verdict(MigrationRow.Verdict.NEW)
                .raw(shorten(raw))
                .build();
    }

    /**
     * A number as an accounting system wrote it.
     *
     * <p>Thousands separators, a currency symbol, a trailing minus, brackets for
     * negative. Anything left unrecognised becomes null rather than zero: zero is
     * a claim about what somebody owes, and a wrong one is a phone call.
     */
    static BigDecimal money(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim();
        boolean negative = cleaned.startsWith("(") && cleaned.endsWith(")") || cleaned.endsWith("-")
                || cleaned.startsWith("-");
        cleaned = cleaned.replaceAll("[^0-9.,]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        // 1.234,56 (much of Europe and Africa) against 1,234.56. When both
        // separators are present the later one is the decimal point and the
        // other is grouping.
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                cleaned = cleaned.replace(".", "").replace(',', '.');
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (lastComma >= 0 || lastDot >= 0) {
            // Only one separator, so its meaning has to be inferred from what
            // follows it. Exactly three digits and nothing else is grouping --
            // "2,500" and "2.500" are both two and a half thousand in somebody's
            // export, and reading either as two-point-five turns a KES 2,500
            // package into a KES 2.50 one across the whole book.
            int at = Math.max(lastComma, lastDot);
            String after = cleaned.substring(at + 1);
            boolean grouping = after.length() == 3
                    && cleaned.indexOf(cleaned.charAt(at)) == at
                    && at > 0;
            cleaned = grouping
                    ? cleaned.substring(0, at) + after
                    : cleaned.substring(0, at) + "." + after;
        }
        try {
            BigDecimal parsed = new BigDecimal(cleaned);
            return negative ? parsed.negate() : parsed;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * A date, or nothing.
     *
     * <p>ISO is taken at face value. A slash or dot format is only taken when the
     * operator has said which order it is in, or when it cannot be misread.
     */
    static Instant date(String value, DateOrder order) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        // Already a timestamp.
        try {
            return Instant.parse(v);
        } catch (Exception notAnInstant) {
            // Keep going; most exports are dates, not instants.
        }
        for (DateTimeFormatter f : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))) {
            try {
                return LocalDateTime.parse(v, f).atZone(ZONE).toInstant();
            } catch (Exception keepGoing) {
                // Try the next shape.
            }
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZONE).toInstant();
        } catch (Exception notIso) {
            // Fall through to the ambiguous formats.
        }
        String[] parts = v.split("[/.\\-]");
        if (parts.length != 3) {
            return null;
        }
        try {
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            int year = Integer.parseInt(parts[2].trim());
            if (year < 100) {
                year += 2000;
            }
            int day;
            int month;
            if (order == DateOrder.DMY) {
                day = a;
                month = b;
            } else if (order == DateOrder.MDY) {
                day = b;
                month = a;
            } else if (a > 12 && b <= 12) {
                day = a;
                month = b;
            } else if (b > 12 && a <= 12) {
                day = b;
                month = a;
            } else {
                // 03/04/2026 with nothing to tell them apart. Refuse: cutting off
                // a paying customer a month early is not worth a guess.
                return null;
            }
            return LocalDate.of(year, month, day).atStartOfDay(ZONE).toInstant();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** UISP writes IP ranges; the first address in one is the customer's. */
    static String firstAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String first = value.split("[,;\\s]+")[0].trim();
        if (first.contains("/")) {
            first = first.substring(0, first.indexOf('/'));
        }
        return first.matches("\\d{1,3}(\\.\\d{1,3}){3}") ? first : null;
    }

    private void resolvePlan(MigrationRow row, List<Plan> allPlans) {
        if (row.getPlanName() == null || row.getPlanName().isBlank()) {
            return;
        }
        String want = row.getPlanName().trim().toLowerCase(Locale.ROOT);
        allPlans.stream()
                .filter(p -> p.getName() != null && p.getName().trim().toLowerCase(Locale.ROOT).equals(want))
                .findFirst()
                .ifPresent(p -> row.setMatchedPlanId(p.getId()));
    }

    /**
     * Decides what can be done with a row.
     *
     * <p>A collision is not a failure. An operator uploads the same export twice
     * far more often than they would admit, and the second upload must recognise
     * its own work rather than duplicate the book.
     */
    private void judge(MigrationRow row, List<Subscriber> existing) {
        // These are not taste: full name, phone, login, fee and paid-until are all
        // NOT NULL on subscribers, so a row missing one cannot become a customer
        // however willing everybody is. Found by promoting against a real
        // database -- against mocks every one of these inserted happily.
        if (row.getFullName() == null || row.getFullName().isBlank()) {
            incomplete(row, "No name in this row, so there is nobody to create.");
            return;
        }
        if (row.getPhoneNumber() == null || row.getPhoneNumber().isBlank()) {
            incomplete(row, "No phone number, and there would be no way to reach them "
                    + "about a bill or an outage.");
            return;
        }
        if (row.getPppoeUsername() == null || row.getPppoeUsername().isBlank()) {
            incomplete(row, "No login, so there would be nothing to connect them with.");
            return;
        }

        // Identity before completeness. On a re-upload the operator wants to hear
        // "already here", not a complaint about a missing price on a customer who
        // was brought across last week.
        Optional<Subscriber> clash = existing.stream()
                .filter(s -> row.getPppoeUsername().equalsIgnoreCase(s.getPppoeUsername()))
                .findFirst();
        if (clash.isPresent()) {
            row.setVerdict(MigrationRow.Verdict.COLLISION);
            row.setVerdictNote("The login '" + row.getPppoeUsername() + "' already belongs to "
                    + clash.get().getFullName() + " here. Skipped, not overwritten.");
            row.setSubscriberId(clash.get().getId());
            return;
        }
        if (row.getMonthlyPrice() == null && row.getMatchedPlanId() == null) {
            incomplete(row, "No price given and no package here matches, so there would be "
                    + "nothing to bill. Create the package first, or put a price in the file.");
            return;
        }
        row.setVerdict(MigrationRow.Verdict.NEW);
        if (row.getPppoePassword() == null || row.getPppoePassword().isBlank()) {
            // Splynx and most others will not export PPPoE secrets in the clear,
            // which is correct of them. Refusing these rows would make the
            // importer useless for the system it most needs to read.
            row.setVerdictNote("The export has no password for this login, so a new one will be "
                    + "made. Their own router will need it before they can connect here.");
        } else if (row.getPaidUntil() == null) {
            row.setVerdictNote("No paid-up date in the export, so they will arrive suspended "
                    + "rather than be given a month.");
        }
    }

    private static void incomplete(MigrationRow row, String why) {
        row.setVerdict(MigrationRow.Verdict.INCOMPLETE);
        row.setVerdictNote(why);
    }

    // --- looking before leaping ---

    /**
     * What the operator is asked to approve.
     *
     * <p>Deliberately includes the money: the question an ISP actually has is not
     * "how many rows parsed" but "what will you charge my customers next month
     * compared with what they pay now".
     */
    @Transactional(readOnly = true)
    public Map<String, Object> plan(Long batchId) {
        MigrationBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("No such import"));
        List<MigrationRow> all = rows.findByBatchIdOrderByIdAsc(batchId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batch.getId());
        out.put("source", batch.getSource());
        out.put("label", batch.getLabel());
        out.put("status", batch.getStatus());
        out.put("total", all.size());
        out.put("ready", all.stream().filter(r -> r.getVerdict() == MigrationRow.Verdict.NEW).count());
        out.put("collisions", all.stream().filter(r -> r.getVerdict() == MigrationRow.Verdict.COLLISION).count());
        out.put("incomplete", all.stream().filter(r -> r.getVerdict() == MigrationRow.Verdict.INCOMPLETE).count());
        out.put("createdAt", batch.getCreatedAt());
        out.put("createdBy", batch.getCreatedBy());
        out.put("promotedAt", batch.getPromotedAt());

        // The packages named in the file that do not exist here. This is the list
        // an operator has to act on before promoting, so it is named, not counted.
        List<String> missingPlans = all.stream()
                .filter(r -> r.getPlanName() != null && !r.getPlanName().isBlank())
                .filter(r -> r.getMatchedPlanId() == null)
                .map(MigrationRow::getPlanName)
                .distinct()
                .sorted()
                .toList();
        out.put("packagesNotHere", missingPlans);

        out.put("monthlyValue", all.stream()
                .filter(r -> r.getVerdict() == MigrationRow.Verdict.NEW)
                .map(MigrationRow::getMonthlyPrice)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        out.put("noPaidUntil", all.stream()
                .filter(r -> r.getVerdict() == MigrationRow.Verdict.NEW)
                .filter(r -> r.getPaidUntil() == null)
                .count());
        return out;
    }

    /** One row's worth of "what changes if we do this". */
    public record Difference(String name, String planName, BigDecimal theirPrice,
                            BigDecimal ourPrice, String note) {
    }

    /**
     * The parallel run: what Zidi would charge, against what they charge now.
     *
     * <p>This is the report that actually sells a migration, and the one that
     * catches a mis-mapped package before three thousand customers get a bill
     * that is wrong by a hundred shillings.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> compare(Long batchId) {
        List<MigrationRow> ready = rows.findByBatchIdAndVerdictOrderByIdAsc(
                batchId, MigrationRow.Verdict.NEW);
        Map<Long, Plan> byId = new LinkedHashMap<>();
        plans.findAll().forEach(p -> byId.put(p.getId(), p));

        List<Difference> differences = new ArrayList<>();
        BigDecimal theirTotal = BigDecimal.ZERO;
        BigDecimal ourTotal = BigDecimal.ZERO;
        int same = 0;

        for (MigrationRow row : ready) {
            BigDecimal theirs = row.getMonthlyPrice();
            Plan matched = row.getMatchedPlanId() == null ? null : byId.get(row.getMatchedPlanId());
            BigDecimal ours = matched != null && matched.getPrice() != null
                    ? matched.getPrice() : theirs;
            if (theirs != null) {
                theirTotal = theirTotal.add(theirs);
            }
            if (ours != null) {
                ourTotal = ourTotal.add(ours);
            }
            if (theirs != null && ours != null && theirs.compareTo(ours) == 0) {
                same++;
                continue;
            }
            String note;
            if (theirs == null) {
                note = "The export gave no price, so this is our package price.";
            } else if (matched == null) {
                note = "No package here matches '" + row.getPlanName() + "', so their price "
                        + "would carry over as-is.";
            } else {
                note = "Their '" + row.getPlanName() + "' maps to our '" + matched.getName() + "'.";
            }
            differences.add(new Difference(row.getFullName(), row.getPlanName(), theirs, ours, note));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compared", ready.size());
        out.put("unchanged", same);
        out.put("theirMonthlyTotal", theirTotal);
        out.put("ourMonthlyTotal", ourTotal);
        out.put("difference", ourTotal.subtract(theirTotal));
        // Capped, because a report nobody can read is not a report. The totals
        // above are over every row, so the number is never wrong -- only the list.
        out.put("differences", differences.stream().limit(200).toList());
        out.put("differenceCount", differences.size());
        return out;
    }

    // --- the cutover ---

    /** What promotion actually did. */
    public record Promoted(int created, int skipped, List<String> problems) {
    }

    /**
     * Turns the staged rows into real customers.
     *
     * <p>Database only: no secret is pushed to a router here. Three thousand API
     * calls inside one request would time out halfway and leave nobody able to
     * say which half — and the customers are still working on the old system's
     * router anyway. Moving them onto Zidi's routers is a separate, resumable
     * job that already exists.
     *
     * <p>A row that fails takes only itself down. A batch that rolls back on row
     * two thousand leaves the operator no better off than before they started.
     */
    /**
     * Turns the staged rows into real customers.
     *
     * <p>Deliberately NOT transactional. Each row is promoted in a transaction of
     * its own, because a batch that rolls back on row two thousand leaves the
     * operator no better off than before they started — and because a failed
     * statement poisons the transaction it was in, so catching the exception and
     * carrying on inside one transaction silently loses the rows that had already
     * worked. That is exactly what it did until a real database was pointed at it.
     */
    public Promoted promote(Long batchId, String by) {
        MigrationBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("No such import"));
        if (batch.getStatus() == MigrationBatch.Status.PROMOTED) {
            throw new IllegalStateException("That import has already been brought across");
        }
        if (batch.getStatus() == MigrationBatch.Status.DISCARDED) {
            throw new IllegalStateException("That import was discarded");
        }

        List<MigrationRow> ready = rows.findByBatchIdAndVerdictOrderByIdAsc(
                batchId, MigrationRow.Verdict.NEW);
        Map<Long, Plan> byId = new LinkedHashMap<>();
        plans.findAll().forEach(p -> byId.put(p.getId(), p));

        int created = 0;
        int skipped = 0;
        int newPasswords = 0;
        List<String> problems = new ArrayList<>();

        for (MigrationRow row : ready) {
            Plan matched = row.getMatchedPlanId() == null ? null : byId.get(row.getMatchedPlanId());
            MigrationRowPromoter.Outcome outcome = promoter.promoteOne(row, batch, matched);
            if (outcome.created()) {
                created++;
                if (outcome.generatedPassword()) {
                    newPasswords++;
                }
            } else {
                skipped++;
                if (outcome.problem() != null) {
                    problems.add(outcome.problem());
                    promoter.noteFailure(row.getId(), outcome.problem());
                    log.warn("Migration row {} could not be promoted: {}",
                            row.getId(), outcome.problem());
                }
            }
        }

        markPromoted(batch, by, created, skipped);
        if (newPasswords > 0) {
            problems.add(newPasswords + " customer(s) came across with a new PPPoE password "
                    + "because the export had none. Their routers will need the new one.");
        }
        log.info("Promoted migration batch {}: {} created, {} skipped, {} new passwords",
                batchId, created, skipped, newPasswords);
        return new Promoted(created, skipped, problems);
    }

    /**
     * Marks the batch done.
     *
     * <p>No transaction of its own and none inherited — {@code promote} is not
     * transactional, so each repository call here commits by itself. That is the
     * point: the batch is recorded as promoted even if some rows were not, which
     * is the truth of what happened.
     */
    private void markPromoted(MigrationBatch batch, String by, int created, int skipped) {
        batch.setStatus(MigrationBatch.Status.PROMOTED);
        batch.setPromotedAt(Instant.now());
        batch.setPromotedBy(by);
        batches.save(batch);
        audit.record(by, "migration.promote",
                "Brought across " + created + " customer(s) from " + batch.getSource()
                        + " (batch " + batch.getId() + ")"
                        + (skipped > 0 ? ", " + skipped + " skipped" : ""));
    }

    /** Throws the staged rows away. Only ever before promotion. */
    @Transactional
    public void discard(Long batchId, String by) {
        MigrationBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("No such import"));
        if (batch.getStatus() == MigrationBatch.Status.PROMOTED) {
            throw new IllegalStateException(
                    "Those customers already exist — deleting the import would not remove them");
        }
        rows.deleteByBatchId(batchId);
        batch.setStatus(MigrationBatch.Status.DISCARDED);
        batches.save(batch);
        audit.record(by, "migration.discard", "Discarded import batch " + batchId);
    }

    @Transactional(readOnly = true)
    public List<MigrationBatch> all() {
        return batches.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<MigrationRow> rowsOf(Long batchId) {
        return rows.findByBatchIdOrderByIdAsc(batchId);
    }

    // --- helpers ---

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Cuts a value to the width of the column it is going into. */
    static String cap(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** The original row, kept for the support call three months later. */
    private static String shorten(Map<String, String> raw) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            if (sb.length() > 3800) {
                sb.append("…");
                break;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
