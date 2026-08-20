package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.BankImport;
import com.spalimited.hotspotbilling.domain.BankTransaction;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.BankImportRepository;
import com.spalimited.hotspotbilling.repository.BankTransactionRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matching money that arrived at the bank to the customer who sent it.
 *
 * <p>Every other rail in this system reconciles itself: a gateway calls back
 * quoting a reference and the payment finds its customer. A bank transfer
 * arrives as an amount and a line of narration, and somebody has to work out who
 * sent it. That is how business and corporate customers pay, which makes it the
 * segment with the most revenue per customer and the least automation.
 *
 * <h2>A wrong match is worse than no match</h2>
 *
 * <p>This is the rule the whole class is built around. Crediting the wrong
 * customer takes two people an afternoon to unpick, and the customer who
 * actually paid is still cut off the whole time. So the only thing applied
 * without a human is a transfer quoting our own payment reference, which cannot
 * be a coincidence. A phone number or a name in the narration is a good guess
 * and is offered as one -- with the reason in words, so whoever confirms it is
 * agreeing with something they can check rather than with a number.
 *
 * <h2>The same statement twice</h2>
 *
 * <p>Operators re-download statements constantly, over overlapping ranges. Every
 * transaction therefore carries a hash of the date, amount, narration and the
 * bank's own reference, unique across the whole table rather than per import, so
 * a row arriving in a second file is recognised as the row it already is. Without
 * that, the second upload credits everybody twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankStatementService {

    /**
     * Our own reference, as written by initiateRenewal: PPPOE-{subscriber}-{payment}.
     * Case-insensitive because banks routinely upper-case narration, and tolerant
     * of the separators a customer might type instead of a hyphen.
     */
    private static final Pattern OUR_REFERENCE =
            Pattern.compile("PPPOE[\\s_/-]*(\\d+)[\\s_/-]+(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Any run of 9 to 13 digits: long enough to be a phone number, short enough not to be an IBAN. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{9,13}");

    private final BankImportRepository imports;
    private final BankTransactionRepository transactions;
    private final SubscriberRepository subscribers;
    private final SubscriptionService subscriptionService;
    private final PhoneNumbers phoneNumbers;
    private final AuditService audit;

    /** One line as the browser parsed it. Credits only; debits are dropped on the way in. */
    public record Row(LocalDate valueDate, String narration, String bankReference, BigDecimal amount) {
    }

    /**
     * Stores a statement and matches what it can.
     *
     * <p>Nothing is credited to anybody here except a transfer quoting our own
     * reference. Everything else lands in a queue.
     */
    @Transactional
    public Map<String, Object> ingest(String filename, String bankName, List<Row> rows, String who) {
        BankImport batch = imports.save(BankImport.builder()
                .filename(filename == null || filename.isBlank() ? "statement.csv" : filename)
                .bankName(bankName)
                .uploadedAt(Instant.now())
                .uploadedBy(who)
                .build());

        // Loaded once. Matching every row against every subscriber would
        // otherwise be a query per row per rule, and a statement is hundreds of
        // rows.
        List<Subscriber> everyone = subscribers.findAll();

        int credits = 0;
        int duplicates = 0;
        int matched = 0;
        int applied = 0;

        for (Row row : rows) {
            if (row.amount() == null || row.amount().signum() <= 0) {
                // A debit is the operator paying somebody. Not our business, and
                // storing them would make the queue mostly noise.
                continue;
            }
            credits++;
            String key = dedupeKey(row);
            if (transactions.existsByDedupeKey(key)) {
                duplicates++;
                continue;
            }

            BankTransaction txn = BankTransaction.builder()
                    .importId(batch.getId())
                    .valueDate(row.valueDate())
                    .narration(clip(row.narration(), 1000))
                    .bankReference(clip(row.bankReference(), 120))
                    .amount(row.amount())
                    .dedupeKey(key)
                    .status(BankTransaction.Status.UNMATCHED)
                    .build();

            Guess guess = guess(row, everyone);
            if (guess != null) {
                txn.setSubscriberId(guess.subscriberId());
                txn.setMatchReason(clip(guess.reason(), 255));
                txn.setStatus(BankTransaction.Status.MATCHED);
                matched++;
            }
            transactions.save(txn);

            // The one automatic case. A transfer quoting PPPOE-42-1093 was
            // generated by us for that customer and that renewal; there is no
            // guessing left to do.
            if (guess != null && guess.certain() && canCover(guess.subscriberId(), row.amount())) {
                apply(txn.getId(), null, "automatic (our own reference)");
                applied++;
            }
        }

        batch.setRowCount(rows.size());
        batch.setCreditCount(credits);
        batch.setDuplicateCount(duplicates);
        batch.setMatchedCount(matched);
        imports.save(batch);

        audit.system("bank.import",
                "Imported " + batch.getFilename() + ": " + credits + " credits, "
                        + duplicates + " already seen, " + applied + " applied automatically");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importId", batch.getId());
        out.put("rows", rows.size());
        out.put("credits", credits);
        out.put("duplicates", duplicates);
        out.put("matched", matched);
        out.put("applied", applied);
        out.put("waiting", credits - duplicates - applied);
        return out;
    }

    /** A candidate customer for one line, and why. */
    private record Guess(Long subscriberId, String reason, boolean certain) {
    }

    /**
     * Who this transfer probably came from.
     *
     * <p>In order of how much it can be trusted, stopping at the first hit. Only
     * the first rule returns certain: the rest are offered to a person.
     */
    private Guess guess(Row row, List<Subscriber> everyone) {
        String narration = row.narration() == null ? "" : row.narration();
        String haystack = narration.toUpperCase(Locale.ROOT);
        // The bank's reference field is worth searching too -- some banks put the
        // account number the payer typed there rather than in the narration.
        String withRef = haystack + " " + (row.bankReference() == null ? ""
                : row.bankReference().toUpperCase(Locale.ROOT));

        // 1. Our own reference.
        Matcher ours = OUR_REFERENCE.matcher(withRef);
        if (ours.find()) {
            Long subscriberId = Long.parseLong(ours.group(1));
            if (subscribers.existsById(subscriberId)) {
                return new Guess(subscriberId, "quoted our payment reference PPPOE-"
                        + subscriberId + "-" + ours.group(2), true);
            }
        }

        // 2. A PPPoE username, as a whole word. Usernames are short and could
        // appear inside another word by chance, so the boundaries matter.
        for (Subscriber s : everyone) {
            String username = s.getPppoeUsername();
            if (username != null && username.length() >= 4
                    && containsWord(withRef, username.toUpperCase(Locale.ROOT))) {
                return new Guess(s.getId(), "the narration contains their username " + username, false);
            }
        }

        // 3. A phone number. Normalised on both sides, because a customer stored
        // as 254712345678 may appear on a statement as 0712345678.
        List<Long> byPhone = new ArrayList<>();
        Matcher digits = DIGIT_RUN.matcher(withRef);
        String phoneReason = null;
        while (digits.find()) {
            String candidate = phoneNumbers.normalise(digits.group());
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (Subscriber s : everyone) {
                String theirs = s.getPhoneNumber() == null ? null
                        : phoneNumbers.normalise(s.getPhoneNumber());
                if (theirs != null && theirs.equals(candidate) && !byPhone.contains(s.getId())) {
                    byPhone.add(s.getId());
                    phoneReason = "the narration contains their phone number " + digits.group();
                }
            }
        }
        // Two customers on one phone number happens -- a household, or a shop
        // paying for two lines -- and picking one of them would be a coin toss.
        if (byPhone.size() == 1) {
            return new Guess(byPhone.get(0), phoneReason, false);
        }
        if (byPhone.size() > 1) {
            return null;
        }

        // 4. A name. Weakest of the four, and only when exactly one customer
        // matches: "JOHN" against a customer list will not do.
        List<Long> byName = new ArrayList<>();
        String matchedName = null;
        for (Subscriber s : everyone) {
            String name = s.getFullName();
            if (name == null || name.strip().length() < 6) {
                continue;
            }
            if (haystack.contains(name.strip().toUpperCase(Locale.ROOT))) {
                byName.add(s.getId());
                matchedName = name;
            }
        }
        if (byName.size() == 1) {
            return new Guess(byName.get(0), "the narration contains the name " + matchedName, false);
        }
        return null;
    }

    /**
     * Credits a transaction to a customer.
     *
     * <p>{@code subscriberId} overrides whatever was guessed, which is what
     * happens when a person corrects a match by hand.
     */
    @Transactional
    public Map<String, Object> apply(Long transactionId, Long subscriberId, String who) {
        BankTransaction txn = transactions.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("No such transaction"));
        if (txn.getStatus() == BankTransaction.Status.APPLIED) {
            // Two people on the same queue, or a double-click. Either way the
            // money has already been credited and doing it again is the one
            // outcome that must never happen.
            return Map.of("ok", false, "message", "This has already been credited.");
        }
        Long target = subscriberId != null ? subscriberId : txn.getSubscriberId();
        if (target == null) {
            throw new IllegalArgumentException("Choose which customer this belongs to first");
        }
        Subscriber sub = subscribers.findById(target)
                .orElseThrow(() -> new IllegalArgumentException("No such subscriber"));

        int months = monthsFor(sub, txn.getAmount());
        SubscriptionPayment payment = subscriptionService.creditBankTransfer(
                target, months, txn.getAmount(), reference(txn));

        txn.setSubscriberId(target);
        txn.setStatus(BankTransaction.Status.APPLIED);
        txn.setPaymentId(payment.getId());
        txn.setDecidedAt(Instant.now());
        txn.setDecidedBy(who);
        transactions.save(txn);

        imports.findById(txn.getImportId()).ifPresent(batch -> {
            batch.setAppliedCount(batch.getAppliedCount() + 1);
            imports.save(batch);
        });

        audit.system("bank.applied", "Credited " + txn.getAmount() + " from the bank to "
                + sub.getFullName() + " (" + months + " month(s)) — " + who);
        log.info("Bank transfer {} credited to subscriber {} as {} month(s)",
                txn.getId(), target, months);

        return Map.of("ok", true, "months", months,
                "message", "Credited " + months + " month(s) to " + sub.getFullName() + ".");
    }

    /** Marks a line as nothing to do with customer payments. */
    @Transactional
    public Map<String, Object> ignore(Long transactionId, String who) {
        BankTransaction txn = transactions.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("No such transaction"));
        if (txn.getStatus() == BankTransaction.Status.APPLIED) {
            return Map.of("ok", false,
                    "message", "This has already been credited and cannot be set aside.");
        }
        txn.setStatus(BankTransaction.Status.IGNORED);
        txn.setDecidedAt(Instant.now());
        txn.setDecidedBy(who);
        transactions.save(txn);
        return Map.of("ok", true, "message", "Set aside.");
    }

    /**
     * How many months an amount buys.
     *
     * <p>Rounded down, and never rounded up to one. A customer on 2,500 a month
     * who sends 500 has part-paid; treating that as a month would give away 2,000
     * shillings and, worse, would look in the record like they were fully paid.
     * Zero months here is what makes {@link #canCover} refuse to auto-apply.
     */
    public int monthsFor(Subscriber sub, BigDecimal amount) {
        BigDecimal fee = sub.getMonthlyFee();
        if (fee == null || fee.signum() <= 0) {
            return 1;
        }
        return amount.divide(fee, 0, RoundingMode.DOWN).intValue();
    }

    /** Whether an amount is enough to auto-apply at all. */
    private boolean canCover(Long subscriberId, BigDecimal amount) {
        return subscribers.findById(subscriberId)
                .map(sub -> monthsFor(sub, amount) >= 1)
                .orElse(false);
    }

    /**
     * What goes in the payment's receipt field.
     *
     * <p>The bank's own reference where there is one, because that is what
     * somebody will search for when the customer rings up with their transfer
     * slip. Falling back to the date and amount is worse but still findable.
     */
    private static String reference(BankTransaction txn) {
        if (txn.getBankReference() != null && !txn.getBankReference().isBlank()) {
            return "BANK " + txn.getBankReference().strip();
        }
        return "BANK " + txn.getValueDate() + " " + txn.getAmount().toPlainString();
    }

    /** Whole-word containment, so "mary" does not match "rosemary". */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean leftClear = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean rightClear = end >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftClear && rightClear) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * The four fields a bank renders identically for the same transaction.
     *
     * <p>Not the row number or the running balance: a statement exported over a
     * different date range has different row numbers and different balances for
     * the very same transaction, which would defeat the whole point.
     */
    static String dedupeKey(Row row) {
        String material = String.join("|",
                row.valueDate() == null ? "" : row.valueDate().toString(),
                row.amount() == null ? "" : row.amount().stripTrailingZeros().toPlainString(),
                row.narration() == null ? "" : row.narration().strip().replaceAll("\\s+", " ")
                        .toUpperCase(Locale.ROOT),
                row.bankReference() == null ? "" : row.bankReference().strip().toUpperCase(Locale.ROOT));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
