package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * A customer's account history and balance, assembled on read from the
 * invoices, payments and adjustments already on file. Nothing is stored as
 * a running total, so a corrected payment or a cancelled invoice shows up
 * immediately and the balance can never disagree with its own workings.
 *
 * <p>Sign convention: positive means the customer owes money; negative
 * means they are in credit. Prepayment is normal here, so credit balances
 * are expected rather than exceptional.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final InvoiceRepository invoices;
    private final SubscriptionPaymentRepository payments;
    private final LedgerAdjustmentRepository adjustments;
    private final SubscriberRepository subscribers;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * One line on a statement.
     *
     * @param signedAmount positive increases the debt, negative reduces it
     * @param balance      the running balance after this line
     */
    public record Entry(LocalDate date, String type, String reference, String description,
                        BigDecimal debit, BigDecimal credit, BigDecimal signedAmount,
                        BigDecimal balance) {
    }

    /**
     * The full statement for one subscriber, oldest first, with a running
     * balance. Cancelled invoices are left out — they were withdrawn, so
     * including them would overstate what was ever charged.
     */
    @Transactional(readOnly = true)
    public List<Entry> statement(Long subscriberId, LocalDate from, LocalDate to) {
        List<Object[]> raw = new ArrayList<>();

        for (Invoice invoice : invoices.findBySubscriberIdOrderByIssuedOnDesc(subscriberId)) {
            if (invoice.getStatus() == Invoice.Status.CANCELLED) {
                continue;
            }
            raw.add(new Object[] { invoice.getIssuedOn(), "INVOICE", invoice.getNumber(),
                    invoice.getMonths() + (invoice.getMonths() == 1 ? " month" : " months") + " of service",
                    invoice.getAmount(), BigDecimal.ZERO, invoice.getCreatedAt() });
        }

        for (SubscriptionPayment payment : payments.findBySubscriberIdOrderByCreatedAtDesc(subscriberId)) {
            if (payment.getStatus() != SubscriptionPayment.Status.SUCCESS) {
                continue;
            }
            var when = payment.getCompletedAt() != null ? payment.getCompletedAt() : payment.getCreatedAt();
            String ref = payment.getMpesaReceiptNumber() != null
                    ? payment.getMpesaReceiptNumber() : "#" + payment.getId();
            raw.add(new Object[] { LocalDate.ofInstant(when, ZONE), "PAYMENT", ref,
                    payment.getMethod() == SubscriptionPayment.Method.CASH ? "Cash payment" : "M-Pesa payment",
                    BigDecimal.ZERO, payment.getAmount(), when });
        }

        for (LedgerAdjustment adj : adjustments.findBySubscriberIdOrderByAppliedOnAsc(subscriberId)) {
            BigDecimal signed = adj.getSignedAmount();
            raw.add(new Object[] { adj.getAppliedOn(), adj.getKind().name(), "#" + adj.getId(),
                    adj.getReason(),
                    signed.signum() > 0 ? adj.getAmount() : BigDecimal.ZERO,
                    signed.signum() < 0 ? adj.getAmount() : BigDecimal.ZERO,
                    adj.getCreatedAt() });
        }

        // Oldest first, tie-broken by the actual timestamp, so several
        // movements on one day still read in the order they happened and the
        // running balance makes sense read top to bottom.
        raw.sort(Comparator
                .<Object[], LocalDate>comparing(r -> (LocalDate) r[0])
                .thenComparing(r -> (java.time.Instant) r[6],
                        Comparator.nullsFirst(Comparator.naturalOrder())));

        List<Entry> out = new ArrayList<>();
        BigDecimal balance = BigDecimal.ZERO;
        for (Object[] r : raw) {
            LocalDate date = (LocalDate) r[0];
            BigDecimal debit = (BigDecimal) r[4];
            BigDecimal credit = (BigDecimal) r[5];
            BigDecimal signed = debit.subtract(credit);
            balance = balance.add(signed);

            // The balance is accumulated over everything, then the window is
            // applied — otherwise a statement for March would start at zero
            // and misstate what was already owed.
            if ((from != null && date.isBefore(from)) || (to != null && date.isAfter(to))) {
                continue;
            }
            out.add(new Entry(date, (String) r[1], (String) r[2], (String) r[3],
                    debit, credit, signed, balance));
        }
        return out;
    }

    /** Closing balance for one subscriber: positive owes, negative in credit. */
    @Transactional(readOnly = true)
    public BigDecimal balance(Long subscriberId) {
        List<Entry> all = statement(subscriberId, null, null);
        return all.isEmpty() ? BigDecimal.ZERO : all.get(all.size() - 1).balance();
    }

    /** Balance plus the figures a summary card needs. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long subscriberId) {
        List<Entry> all = statement(subscriberId, null, null);
        BigDecimal invoiced = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (Entry e : all) {
            invoiced = invoiced.add(e.debit());
            paid = paid.add(e.credit());
        }
        BigDecimal balance = all.isEmpty() ? BigDecimal.ZERO : all.get(all.size() - 1).balance();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", all.size());
        out.put("invoiced", invoiced);
        out.put("paid", paid);
        out.put("balance", balance);
        out.put("owes", balance.signum() > 0 ? balance : BigDecimal.ZERO);
        out.put("credit", balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO);
        out.put("lastEntry", all.isEmpty() ? null : all.get(all.size() - 1).date());
        return out;
    }

    /** Everyone with a non-zero balance, worst arrears first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> outstanding() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Subscriber sub : subscribers.findAll()) {
            BigDecimal balance = balance(sub.getId());
            if (balance.signum() == 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subscriberId", sub.getId());
            row.put("fullName", sub.getFullName());
            row.put("phoneNumber", sub.getPhoneNumber());
            row.put("account", sub.getPppoeUsername());
            row.put("status", sub.getStatus());
            row.put("balance", balance);
            row.put("owes", balance.signum() > 0);
            out.add(row);
        }
        out.sort((a, b) -> ((BigDecimal) b.get("balance")).compareTo((BigDecimal) a.get("balance")));
        return out;
    }

    @Transactional
    public LedgerAdjustment adjust(Long subscriberId, LedgerAdjustment.Kind kind, BigDecimal amount,
                                   String reason, LocalDate appliedOn, String createdBy) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Enter a positive amount — the kind decides the direction");
        }
        Subscriber sub = subscribers.findById(subscriberId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown subscriber: " + subscriberId));

        // A write-off should not exceed the debt, or the customer ends up in
        // credit for money they never paid.
        if (kind == LedgerAdjustment.Kind.WRITE_OFF) {
            BigDecimal owed = balance(subscriberId);
            if (owed.signum() <= 0) {
                throw new IllegalStateException(sub.getFullName() + " does not owe anything to write off");
            }
            if (amount.compareTo(owed) > 0) {
                throw new IllegalArgumentException("That is more than the "
                        + owed.toPlainString() + " outstanding — write off at most the balance");
            }
        }

        LedgerAdjustment saved = adjustments.save(LedgerAdjustment.builder()
                .subscriber(sub)
                .kind(kind)
                .amount(amount)
                .reason(reason)
                .appliedOn(appliedOn)
                .createdBy(createdBy)
                .build());
        log.info("Ledger adjustment {} of {} for {}", kind, amount, sub.getPppoeUsername());
        return saved;
    }

    @Transactional
    public void removeAdjustment(Long id) {
        adjustments.deleteById(id);
    }
}
