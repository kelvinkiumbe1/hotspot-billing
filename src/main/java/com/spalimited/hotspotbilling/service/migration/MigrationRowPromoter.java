package com.spalimited.hotspotbilling.service.migration;

import com.spalimited.hotspotbilling.domain.MigrationBatch;
import com.spalimited.hotspotbilling.domain.MigrationRow;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.MigrationRowRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * One migrated customer, in its own transaction.
 *
 * <p>This exists because of a bug that only appeared against a real database. The
 * import caught a failing row, logged it and carried on — which reads as correct
 * and is worthless: once a statement fails, PostgreSQL aborts the whole
 * transaction and every later statement is refused, so the rows that had already
 * succeeded were rolled back with it and the audit entry never got written. The
 * unit tests passed throughout, because a mocked repository has no transaction to
 * abort.
 *
 * <p>A separate bean is what makes {@code REQUIRES_NEW} take effect: calling a
 * transactional method on {@code this} goes straight down the stack and past the
 * proxy that would have started the new transaction.
 */
@Service
@RequiredArgsConstructor
public class MigrationRowPromoter {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private final SubscriberRepository subscribers;
    private final MigrationRowRepository rows;

    /** What became of one row. */
    public record Outcome(boolean created, boolean generatedPassword, String problem) {
    }

    /**
     * Creates one customer, or fails alone.
     *
     * <p>Database only. No secret is pushed to a router here: three thousand API
     * calls inside one request would time out halfway with nobody able to say
     * which half, and the customers are still being served by the old system's
     * router anyway.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome promoteOne(MigrationRow row, MigrationBatch batch, Plan matched) {
        try {
            if (subscribers.findByPppoeUsername(row.getPppoeUsername()).isPresent()) {
                // Somebody was added by hand between staging and promoting.
                row.setVerdict(MigrationRow.Verdict.COLLISION);
                row.setVerdictNote("That login was taken between staging and promoting.");
                rows.save(row);
                return new Outcome(false, false, null);
            }

            boolean generated = row.getPppoePassword() == null || row.getPppoePassword().isBlank();
            String password = generated ? newPassword() : row.getPppoePassword();

            // Not knowing when somebody is paid up to is not a reason to give them
            // a month. They arrive expired, which means suspended, and the
            // operator sets the date from whatever the old system's invoices say.
            Instant paidUntil = row.getPaidUntil() != null ? row.getPaidUntil() : Instant.now();
            boolean paidUp = paidUntil.isAfter(Instant.now());

            Subscriber sub = subscribers.save(Subscriber.builder()
                    .fullName(row.getFullName())
                    .phoneNumber(row.getPhoneNumber())
                    .pppoeUsername(row.getPppoeUsername())
                    .pppoePassword(password)
                    .bandwidth(matched != null ? matched.getBandwidth() : null)
                    .monthlyFee(row.getMonthlyPrice() != null ? row.getMonthlyPrice()
                            : (matched != null ? matched.getPrice() : null))
                    .staticIp(row.getStaticIp())
                    .paidUntil(paidUntil)
                    .status(paidUp ? Subscriber.Status.ACTIVE : Subscriber.Status.SUSPENDED)
                    .connectionType(row.getStaticIp() != null
                            ? Subscriber.ConnectionType.STATIC : Subscriber.ConnectionType.PPPOE)
                    .migratedFrom(batch.getSource().name())
                    .migratedRef(row.getExternalId())
                    .migratedAt(Instant.now())
                    .createdBy("migration")
                    .build());

            row.setSubscriberId(sub.getId());
            if (generated) {
                row.setVerdictNote("Brought across with a new PPPoE password, because the export "
                        + "did not contain one. Their own router still has the old password.");
            }
            rows.save(row);
            return new Outcome(true, generated, null);
        } catch (Exception e) {
            // Thrown out so the caller's own transaction is untouched: this one is
            // already doomed and rolls back on its own.
            String who = row.getFullName() == null ? "row " + row.getId() : row.getFullName();
            return new Outcome(false, false, who + ": " + rootMessage(e));
        }
    }

    /**
     * Records that a row could not be promoted, in a transaction of its own.
     *
     * <p>Separate from {@link #promoteOne} because the transaction that failed
     * cannot be used to write down that it failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void noteFailure(Long rowId, String problem) {
        rows.findById(rowId).ifPresent(row -> {
            row.setVerdictNote(trim(problem));
            rows.save(row);
        });
    }

    /**
     * A password for a customer whose old one never left the previous system.
     *
     * <p>Splynx and most of the others will not export PPPoE secrets in the
     * clear, which is correct of them and awkward here. Refusing those rows would
     * make the importer useless for the systems it most needs to read, so one is
     * made and the row says so — the operator has to get it onto the customer's
     * router either way.
     */
    static String newPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : trim(message);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 400 ? oneLine.substring(0, 400) + "…" : oneLine;
    }
}
