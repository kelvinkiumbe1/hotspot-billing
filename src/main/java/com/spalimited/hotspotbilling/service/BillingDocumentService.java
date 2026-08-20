package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CreditNote;
import com.spalimited.hotspotbilling.domain.Invoice;
import com.spalimited.hotspotbilling.domain.LedgerAdjustment;
import com.spalimited.hotspotbilling.domain.ProformaInvoice;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.TaxSettings;
import com.spalimited.hotspotbilling.repository.CreditNoteRepository;
import com.spalimited.hotspotbilling.repository.InvoiceRepository;
import com.spalimited.hotspotbilling.repository.ProformaInvoiceRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The two documents a business customer asks for: a quote, and a refund.
 *
 * <h2>Proforma invoices</h2>
 *
 * <p>A priced quote with a number on it, issued before any money moves, because
 * a company's finance department needs a document to raise a payment against.
 * Until now the only way to give a business customer a number to pay was to
 * issue a real invoice -- which then sat in the arrears list and the dunning
 * queue as an unpaid debt they had never agreed to owe. A proforma is not a
 * debt, appears in no arrears list, and chases nobody.
 *
 * <p>It becomes a debt at exactly one moment: when somebody converts it. That is
 * a deliberate act with a person's name on it.
 *
 * <h2>Credit notes</h2>
 *
 * <p>A credit note reverses an invoice. The ledger already had a CREDIT_NOTE
 * adjustment kind, which moves the balance -- so this does not replace it, it
 * creates one. What the document adds is the three things the ledger row has
 * never had: a reference somebody can quote, the invoice it reverses, and the
 * VAT split, reversed in the same proportion the invoice charged it.
 *
 * <p>That last part is the one that matters beyond bookkeeping. Crediting 2,500
 * gross against a VAT-inclusive invoice does not reverse 2,500 of net revenue;
 * it reverses roughly 2,155 of net and 345 of tax. Getting that wrong overstates
 * a VAT reclaim, which is the sort of error a tax authority notices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingDocumentService {

    /** How long a quote is good for unless somebody says otherwise. */
    private static final int DEFAULT_VALID_DAYS = 30;

    private final ProformaInvoiceRepository proformas;
    private final CreditNoteRepository creditNotes;
    private final InvoiceRepository invoices;
    private final SubscriberRepository subscribers;
    private final TaxService taxService;
    private final LedgerService ledgerService;
    private final InvoiceService invoiceService;
    private final AuditService audit;

    // --- Proformas ---

    /**
     * Issues a quote.
     *
     * <p>The amount is the customer's own monthly fee times the months unless
     * an amount is passed, because a business quote is often a negotiated figure
     * rather than the list price.
     */
    @Transactional
    public ProformaInvoice quote(Long subscriberId, int months, BigDecimal overrideAmount,
                                 String description, Integer validDays, String who) {
        Subscriber sub = subscribers.findById(subscriberId)
                .orElseThrow(() -> new IllegalArgumentException("No such subscriber"));
        if (months < 1) {
            throw new IllegalArgumentException("A quote has to be for at least one month");
        }
        BigDecimal charge = overrideAmount != null && overrideAmount.signum() > 0
                ? overrideAmount
                : sub.getMonthlyFee().multiply(BigDecimal.valueOf(months));

        // The split is worked out now and stored, exactly as InvoiceService does
        // it. A quote whose tax quietly changed because somebody edited the VAT
        // rate afterwards no longer matches the paper the customer is holding.
        TaxSettings tax = taxService.settings();
        TaxSettings.Split split = tax.split(charge);

        int days = validDays != null && validDays > 0 ? validDays : DEFAULT_VALID_DAYS;
        ProformaInvoice quote = proformas.save(ProformaInvoice.builder()
                .number(nextNumber("PF", proformas.countByNumberStartingWith(prefix("PF"))))
                .subscriberId(subscriberId)
                .amount(split.gross())
                .netAmount(split.net())
                .vatAmount(split.vat())
                .vatRate(tax.isVatEnabled() ? tax.getVatRate() : null)
                .vatInclusive(tax.isVatEnabled() ? tax.isPricesIncludeVat() : null)
                .months(months)
                .description(description)
                .issuedOn(LocalDate.now())
                .validUntil(LocalDate.now().plusDays(days))
                .status(ProformaInvoice.Status.ISSUED)
                .createdBy(who)
                .build());

        audit.system("proforma.issued", "Quote " + quote.getNumber() + " for "
                + sub.getFullName() + ": " + split.gross() + " (" + months + " month(s))");
        log.info("Issued proforma {} for {} ({})", quote.getNumber(), sub.getPppoeUsername(),
                split.gross());
        return quote;
    }

    /**
     * Turns a quote into a real invoice.
     *
     * <p>The one place a proforma becomes money owed. An expired quote is refused
     * rather than quietly honoured: whether to hold last quarter's price is a
     * commercial decision and not one this method should make on somebody's
     * behalf. Re-quoting is one click.
     */
    @Transactional
    public Invoice convert(Long proformaId, String who) {
        ProformaInvoice quote = proformas.findById(proformaId)
                .orElseThrow(() -> new IllegalArgumentException("No such quote"));
        if (quote.getStatus() == ProformaInvoice.Status.CONVERTED) {
            throw new IllegalStateException("That quote has already become invoice "
                    + invoices.findById(quote.getInvoiceId()).map(Invoice::getNumber).orElse("?"));
        }
        if (quote.getStatus() == ProformaInvoice.Status.CANCELLED) {
            throw new IllegalStateException("That quote was cancelled");
        }
        if (quote.getValidUntil().isBefore(LocalDate.now())) {
            throw new IllegalStateException("That quote expired on " + quote.getValidUntil()
                    + ". Issue a new one rather than honouring an old price by accident.");
        }
        Subscriber sub = subscribers.findById(quote.getSubscriberId())
                .orElseThrow(() -> new IllegalArgumentException("That customer no longer exists"));

        Invoice invoice = invoiceService.issue(sub, quote.getMonths());
        quote.setStatus(ProformaInvoice.Status.CONVERTED);
        quote.setInvoiceId(invoice.getId());
        quote.setConvertedAt(Instant.now());
        proformas.save(quote);

        audit.system("proforma.converted", "Quote " + quote.getNumber()
                + " became invoice " + invoice.getNumber() + " — " + who);
        return invoice;
    }

    @Transactional
    public ProformaInvoice cancelQuote(Long proformaId, String who) {
        ProformaInvoice quote = proformas.findById(proformaId)
                .orElseThrow(() -> new IllegalArgumentException("No such quote"));
        if (quote.getStatus() == ProformaInvoice.Status.CONVERTED) {
            throw new IllegalStateException(
                    "That quote is already an invoice. Credit the invoice instead.");
        }
        quote.setStatus(ProformaInvoice.Status.CANCELLED);
        proformas.save(quote);
        audit.system("proforma.cancelled", "Quote " + quote.getNumber() + " cancelled — " + who);
        return quote;
    }

    @Transactional(readOnly = true)
    public List<ProformaInvoice> recentQuotes() {
        return proformas.findTop200ByOrderByIssuedOnDesc();
    }

    @Transactional(readOnly = true)
    public List<ProformaInvoice> quotesFor(Long subscriberId) {
        return proformas.findBySubscriberIdOrderByIssuedOnDesc(subscriberId);
    }

    // --- Credit notes ---

    /**
     * Issues a credit note, moves the customer's balance, and reverses the VAT.
     *
     * @param invoiceId the invoice being reversed, or null for a goodwill credit
     */
    @Transactional
    public CreditNote credit(Long subscriberId, Long invoiceId, BigDecimal amount,
                             String reason, String who) {
        Subscriber sub = subscribers.findById(subscriberId)
                .orElseThrow(() -> new IllegalArgumentException("No such subscriber"));
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Enter a positive amount — a credit note is a credit by definition");
        }
        if (reason == null || reason.isBlank()) {
            // Not bureaucracy. A credit note with no reason is unanswerable when
            // somebody asks in a year why the customer was given money back.
            throw new IllegalArgumentException("Say why this is being credited");
        }

        Invoice invoice = null;
        BigDecimal vatRate = null;
        BigDecimal net = null;
        BigDecimal vat = null;

        if (invoiceId != null) {
            invoice = invoices.findById(invoiceId)
                    .orElseThrow(() -> new IllegalArgumentException("No such invoice"));
            if (!invoice.getSubscriber().getId().equals(subscriberId)) {
                throw new IllegalArgumentException("That invoice belongs to a different customer");
            }
            BigDecimal already = creditNotes.creditedAgainst(invoiceId).orElse(BigDecimal.ZERO);
            BigDecimal room = invoice.getAmount().subtract(already);
            if (amount.compareTo(room) > 0) {
                throw new IllegalArgumentException("Invoice " + invoice.getNumber() + " is for "
                        + invoice.getAmount().toPlainString()
                        + (already.signum() > 0 ? " with " + already.toPlainString()
                                + " already credited" : "")
                        + ". At most " + room.toPlainString() + " can be credited.");
            }
            // Reversed in the proportion the invoice charged it, not at today's
            // rate: the invoice may predate a rate change, and what is being
            // reversed is what was charged.
            if (invoice.getVatAmount() != null && invoice.getAmount().signum() > 0) {
                vat = invoice.getVatAmount()
                        .multiply(amount)
                        .divide(invoice.getAmount(), 2, RoundingMode.HALF_UP);
                net = amount.subtract(vat);
                vatRate = invoice.getVatRate();
            }
        }

        if (net == null) {
            // A standalone credit has no invoice to take a rate from, so it uses
            // the current settings -- the same thing an invoice issued today would.
            TaxSettings tax = taxService.settings();
            TaxSettings.Split split = tax.split(amount);
            net = split.net();
            vat = split.vat();
            vatRate = tax.isVatEnabled() ? tax.getVatRate() : null;
        }

        // The balance movement stays where it always was. This document does not
        // replace the ledger, it explains one row of it.
        LedgerAdjustment adjustment = ledgerService.adjust(subscriberId,
                LedgerAdjustment.Kind.CREDIT_NOTE, amount, reason, LocalDate.now(), who);

        CreditNote note = creditNotes.save(CreditNote.builder()
                .number(nextNumber("CN", creditNotes.countByNumberStartingWith(prefix("CN"))))
                .subscriberId(subscriberId)
                .invoiceId(invoiceId)
                .amount(amount)
                .netAmount(net)
                .vatAmount(vat)
                .vatRate(vatRate)
                .reason(reason.strip())
                .issuedOn(LocalDate.now())
                .adjustmentId(adjustment.getId())
                .createdBy(who)
                .build());

        // An invoice credited in full is cancelled rather than left looking
        // unpaid, which would keep chasing the customer for money that has been
        // given back.
        if (invoice != null && invoice.getStatus() == Invoice.Status.UNPAID) {
            BigDecimal credited = creditNotes.creditedAgainst(invoiceId).orElse(BigDecimal.ZERO);
            if (credited.compareTo(invoice.getAmount()) >= 0) {
                invoice.setStatus(Invoice.Status.CANCELLED);
                invoices.save(invoice);
                log.info("Invoice {} cancelled — fully credited by {}",
                        invoice.getNumber(), note.getNumber());
            }
        }

        audit.system("creditnote.issued", "Credit note " + note.getNumber() + " for "
                + sub.getFullName() + ": " + amount
                + (invoice != null ? " against " + invoice.getNumber() : " (goodwill)")
                + " — " + reason);
        log.info("Issued credit note {} for {} ({}, net {} + VAT {})", note.getNumber(),
                sub.getPppoeUsername(), amount, net, vat);
        return note;
    }

    @Transactional(readOnly = true)
    public List<CreditNote> recentCreditNotes() {
        return creditNotes.findTop200ByOrderByIssuedOnDesc();
    }

    @Transactional(readOnly = true)
    public List<CreditNote> creditNotesFor(Long subscriberId) {
        return creditNotes.findBySubscriberIdOrderByIssuedOnDesc(subscriberId);
    }

    /** How much of an invoice is still creditable. */
    @Transactional(readOnly = true)
    public BigDecimal creditableOn(Long invoiceId) {
        Invoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("No such invoice"));
        return invoice.getAmount()
                .subtract(creditNotes.creditedAgainst(invoiceId).orElse(BigDecimal.ZERO));
    }

    // --- Numbering ---

    /**
     * Document numbers, sharing the operator's invoice prefix.
     *
     * <p>PF and CN keep the two sequences apart from each other and from
     * invoices, so a number always says what kind of document it is. Counted per
     * year, like invoices, which is what an accountant expects.
     */
    private String prefix(String kind) {
        return taxService.settings().getInvoicePrefix() + "-" + kind + "-"
                + LocalDate.now().getYear() + "-";
    }

    private String nextNumber(String kind, long existing) {
        return prefix(kind) + String.format("%06d", existing + 1);
    }
}
