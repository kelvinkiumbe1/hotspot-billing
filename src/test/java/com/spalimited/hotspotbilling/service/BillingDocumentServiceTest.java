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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quotes and credit notes.
 *
 * <p>Two things are worth the care. A quote must not behave like a debt -- the
 * whole reason it exists is that issuing a real invoice put business customers
 * into the dunning queue for money they had never agreed to owe -- and a credit
 * note must reverse the VAT in the proportion the invoice charged it, not at
 * today's rate. Crediting 2,500 gross against a VAT-inclusive invoice reverses
 * about 2,155 of net and 345 of tax; getting it wrong overstates a VAT reclaim,
 * which is the sort of arithmetic a revenue authority checks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingDocumentServiceTest {

    @Mock
    private ProformaInvoiceRepository proformas;

    @Mock
    private CreditNoteRepository creditNotes;

    @Mock
    private InvoiceRepository invoices;

    @Mock
    private SubscriberRepository subscribers;

    @Mock
    private TaxService taxService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private InvoiceService invoiceService;

    @Mock
    private AuditService audit;

    @InjectMocks
    private BillingDocumentService service;

    private Subscriber acme;
    private Invoice issued;
    private final List<CreditNote> storedNotes = new ArrayList<>();
    private final List<ProformaInvoice> storedQuotes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        storedNotes.clear();
        storedQuotes.clear();

        acme = Subscriber.builder().id(7L).fullName("Acme Ltd").pppoeUsername("acme")
                .phoneNumber("254700000000").monthlyFee(new BigDecimal("20000")).build();
        when(subscribers.findById(7L)).thenReturn(Optional.of(acme));

        Subscriber other = Subscriber.builder().id(8L).fullName("Someone Else")
                .pppoeUsername("other").monthlyFee(new BigDecimal("2500")).build();
        when(subscribers.findById(8L)).thenReturn(Optional.of(other));

        // 16% VAT, prices inclusive: the Kenyan default and the awkward case,
        // because the tax has to be extracted from the gross rather than added.
        TaxSettings tax = new TaxSettings();
        tax.setVatEnabled(true);
        tax.setVatRate(new BigDecimal("16.00"));
        tax.setPricesIncludeVat(true);
        tax.setInvoicePrefix("SPA");
        when(taxService.settings()).thenReturn(tax);

        // An invoice for 20,000 gross, VAT-inclusive at 16%.
        issued = Invoice.builder().id(100L).number("SPA-2026-000042").subscriber(acme)
                .amount(new BigDecimal("20000.00"))
                .netAmount(new BigDecimal("17241.38"))
                .vatAmount(new BigDecimal("2758.62"))
                .vatRate(new BigDecimal("16.00"))
                .vatInclusive(true)
                .months(1)
                .issuedOn(LocalDate.now().minusDays(10))
                .dueOn(LocalDate.now().plusDays(20))
                .status(Invoice.Status.UNPAID)
                .build();
        when(invoices.findById(100L)).thenReturn(Optional.of(issued));
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));

        when(proformas.countByNumberStartingWith(anyString())).thenAnswer(i -> (long) storedQuotes.size());
        when(proformas.save(any())).thenAnswer(i -> {
            ProformaInvoice q = i.getArgument(0);
            if (q.getId() == null) {
                q.setId((long) (storedQuotes.size() + 1));
                storedQuotes.add(q);
            }
            return q;
        });
        when(proformas.findById(anyLong())).thenAnswer(i ->
                storedQuotes.stream().filter(q -> q.getId().equals(i.getArgument(0))).findFirst());

        when(creditNotes.countByNumberStartingWith(anyString())).thenAnswer(i -> (long) storedNotes.size());
        when(creditNotes.save(any())).thenAnswer(i -> {
            CreditNote c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId((long) (storedNotes.size() + 1));
                storedNotes.add(c);
            }
            return c;
        });
        // Cumulative credits against an invoice, from what has actually been saved.
        when(creditNotes.creditedAgainst(anyLong())).thenAnswer(i -> Optional.of(
                storedNotes.stream()
                        .filter(c -> i.getArgument(0).equals(c.getInvoiceId()))
                        .map(CreditNote::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)));

        when(ledgerService.adjust(anyLong(), any(), any(), anyString(), any(), anyString()))
                .thenAnswer(i -> LedgerAdjustment.builder().id(55L).build());
    }

    // --- Quotes ---

    @Test
    @DisplayName("a quote is priced, numbered, and is not a debt")
    void quoteIsIssued() {
        ProformaInvoice quote = service.quote(7L, 3, null, "Fibre, 3 months", null, "grace");

        assertThat(quote.getNumber()).isEqualTo("SPA-PF-" + LocalDate.now().getYear() + "-000001");
        assertThat(quote.getAmount()).isEqualByComparingTo("60000");
        assertThat(quote.getStatus()).isEqualTo(ProformaInvoice.Status.ISSUED);
        assertThat(quote.isLive()).isTrue();
        // The whole point: issuing a quote must not create an invoice, because an
        // invoice is a debt and would put this customer into the dunning queue.
        verify(invoiceService, never()).issue(any(), anyInt());
    }

    @Test
    @DisplayName("a negotiated price overrides the list fee")
    void quoteCanBeNegotiated() {
        ProformaInvoice quote = service.quote(7L, 12, new BigDecimal("200000"), "Annual deal",
                null, "grace");

        // 12 x 20,000 would be 240,000. The negotiated figure is what stands.
        assertThat(quote.getAmount()).isEqualByComparingTo("200000");
        assertThat(quote.getMonths()).isEqualTo(12);
    }

    @Test
    @DisplayName("the VAT split is stored on the quote so a later rate change cannot rewrite it")
    void quoteStoresItsOwnTax() {
        ProformaInvoice quote = service.quote(7L, 1, null, null, null, "grace");

        assertThat(quote.getNetAmount()).isNotNull();
        assertThat(quote.getVatAmount()).isNotNull();
        assertThat(quote.getNetAmount().add(quote.getVatAmount()))
                .isEqualByComparingTo(quote.getAmount());
        assertThat(quote.getVatRate()).isEqualByComparingTo("16.00");
    }

    @Test
    @DisplayName("a quote expires, and converting an expired one is refused rather than honoured")
    void expiredQuoteWillNotConvert() {
        ProformaInvoice quote = service.quote(7L, 1, null, null, 30, "grace");
        quote.setValidUntil(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.convert(quote.getId(), "grace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");

        // Whether to honour last quarter's price is a commercial decision, not
        // one this method should make on somebody's behalf.
        verify(invoiceService, never()).issue(any(), anyInt());
        assertThat(quote.isLive()).isFalse();
    }

    @Test
    @DisplayName("converting a quote issues the invoice and ties the two together")
    void convertIssuesInvoice() {
        ProformaInvoice quote = service.quote(7L, 2, null, null, null, "grace");
        Invoice made = Invoice.builder().id(200L).number("SPA-2026-000100").subscriber(acme).build();
        when(invoiceService.issue(acme, 2)).thenReturn(made);
        when(invoices.findById(200L)).thenReturn(Optional.of(made));

        Invoice result = service.convert(quote.getId(), "grace");

        assertThat(result.getNumber()).isEqualTo("SPA-2026-000100");
        assertThat(quote.getStatus()).isEqualTo(ProformaInvoice.Status.CONVERTED);
        assertThat(quote.getInvoiceId()).isEqualTo(200L);
        assertThat(quote.getConvertedAt()).isNotNull();
    }

    @Test
    @DisplayName("a quote cannot be converted twice")
    void convertOnlyOnce() {
        ProformaInvoice quote = service.quote(7L, 1, null, null, null, "grace");
        Invoice made = Invoice.builder().id(200L).number("SPA-2026-000100").subscriber(acme).build();
        when(invoiceService.issue(any(), anyInt())).thenReturn(made);
        when(invoices.findById(200L)).thenReturn(Optional.of(made));
        service.convert(quote.getId(), "grace");

        assertThatThrownBy(() -> service.convert(quote.getId(), "grace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPA-2026-000100");
    }

    @Test
    @DisplayName("a cancelled quote cannot be converted")
    void cancelledQuoteWillNotConvert() {
        ProformaInvoice quote = service.quote(7L, 1, null, null, null, "grace");
        service.cancelQuote(quote.getId(), "grace");

        assertThatThrownBy(() -> service.convert(quote.getId(), "grace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled");
    }

    // --- Credit notes ---

    @Test
    @DisplayName("a credit note reverses the VAT in the invoice's own proportion")
    void vatIsReversedProportionally() {
        // Half of a 20,000 invoice.
        CreditNote note = service.credit(7L, 100L, new BigDecimal("10000"), "Two weeks down", "grace");

        // Half the invoice's VAT, not 16% of 10,000 (which would be 1,600) and
        // not 10,000/1.16 recomputed at today's rate.
        assertThat(note.getVatAmount()).isEqualByComparingTo("1379.31");
        assertThat(note.getNetAmount()).isEqualByComparingTo("8620.69");
        assertThat(note.getNetAmount().add(note.getVatAmount())).isEqualByComparingTo("10000");
        assertThat(note.getVatRate()).isEqualByComparingTo("16.00");
    }

    @Test
    @DisplayName("an invoice issued at an older VAT rate is reversed at that rate, not today's")
    void oldRateIsHonoured() {
        // This is the test that actually proves the proportional reversal, and it
        // took a surviving mutation to notice the others did not. When the
        // invoice was issued at the same rate the settings hold now, working the
        // VAT out proportionally and recomputing it from scratch give identical
        // answers -- so every case above passes either way.
        //
        // A rate change is when they diverge, and it is the whole reason the
        // split is stored on the invoice in the first place.
        Invoice atFourteen = Invoice.builder().id(101L).number("SPA-2025-000007").subscriber(acme)
                .amount(new BigDecimal("20000.00"))
                .netAmount(new BigDecimal("17543.86"))
                .vatAmount(new BigDecimal("2456.14"))
                .vatRate(new BigDecimal("14.00"))
                .vatInclusive(true)
                .months(1)
                .issuedOn(LocalDate.now().minusYears(1))
                .dueOn(LocalDate.now().minusYears(1).plusDays(30))
                .status(Invoice.Status.PAID)
                .build();
        when(invoices.findById(101L)).thenReturn(Optional.of(atFourteen));

        CreditNote note = service.credit(7L, 101L, new BigDecimal("10000"), "Refund", "grace");

        // Half of the 14% VAT that was actually charged: 1228.07.
        // Recomputing at today's 16% would give 1379.31 and overstate the
        // reclaim by 151.24 on a single 10,000 refund.
        assertThat(note.getVatAmount()).isEqualByComparingTo("1228.07");
        assertThat(note.getNetAmount()).isEqualByComparingTo("8771.93");
        assertThat(note.getVatRate()).isEqualByComparingTo("14.00");
    }

    @Test
    @DisplayName("a full credit reverses the invoice's whole VAT figure exactly")
    void fullCreditReversesWholeVat() {
        CreditNote note = service.credit(7L, 100L, new BigDecimal("20000.00"), "Cancelled", "grace");

        assertThat(note.getVatAmount()).isEqualByComparingTo(issued.getVatAmount());
        assertThat(note.getNetAmount()).isEqualByComparingTo(issued.getNetAmount());
    }

    @Test
    @DisplayName("an invoice credited in full is cancelled rather than left looking unpaid")
    void fullyCreditedInvoiceIsCancelled() {
        service.credit(7L, 100L, new BigDecimal("20000.00"), "Cancelled", "grace");

        // Left UNPAID, the dunning queue would keep chasing money that has been
        // given back.
        assertThat(issued.getStatus()).isEqualTo(Invoice.Status.CANCELLED);
    }

    @Test
    @DisplayName("a partial credit leaves the invoice unpaid")
    void partialCreditLeavesInvoiceOpen() {
        service.credit(7L, 100L, new BigDecimal("5000"), "Goodwill", "grace");

        assertThat(issued.getStatus()).isEqualTo(Invoice.Status.UNPAID);
    }

    @Test
    @DisplayName("more than the invoice cannot be credited")
    void cannotOverCredit() {
        assertThatThrownBy(() ->
                service.credit(7L, 100L, new BigDecimal("25000"), "Oops", "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At most 20000.00");

        assertThat(storedNotes).isEmpty();
        verify(ledgerService, never()).adjust(anyLong(), any(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("two credit notes cannot together exceed the invoice")
    void cumulativeCreditIsCapped() {
        service.credit(7L, 100L, new BigDecimal("15000"), "First", "grace");

        assertThatThrownBy(() ->
                service.credit(7L, 100L, new BigDecimal("6000"), "Second", "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15000");

        assertThat(storedNotes).hasSize(1);
    }

    @Test
    @DisplayName("crediting up to the exact remainder is allowed")
    void remainderCanBeCredited() {
        service.credit(7L, 100L, new BigDecimal("15000"), "First", "grace");
        CreditNote second = service.credit(7L, 100L, new BigDecimal("5000.00"), "Rest", "grace");

        assertThat(second.getNumber()).endsWith("000002");
        assertThat(storedNotes).hasSize(2);
        assertThat(issued.getStatus()).isEqualTo(Invoice.Status.CANCELLED);
    }

    @Test
    @DisplayName("a credit note moves the customer's balance through the ledger")
    void balanceMovesThroughTheLedger() {
        CreditNote note = service.credit(7L, 100L, new BigDecimal("2500"), "Outage", "grace");

        // The document explains one ledger row rather than replacing the ledger.
        verify(ledgerService).adjust(eq(7L), eq(LedgerAdjustment.Kind.CREDIT_NOTE),
                eq(new BigDecimal("2500")), eq("Outage"), any(), eq("grace"));
        assertThat(note.getAdjustmentId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("a goodwill credit with no invoice uses today's tax settings")
    void goodwillCreditUsesCurrentTax() {
        CreditNote note = service.credit(7L, null, new BigDecimal("1160"), "Apology", "grace");

        assertThat(note.getInvoiceId()).isNull();
        assertThat(note.getNetAmount().add(note.getVatAmount())).isEqualByComparingTo("1160");
    }

    @Test
    @DisplayName("a credit note needs a reason")
    void reasonIsRequired() {
        assertThatThrownBy(() -> service.credit(7L, 100L, new BigDecimal("100"), "   ", "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("why");
    }

    @Test
    @DisplayName("a negative credit note is refused, not silently flipped")
    void negativeIsRefused() {
        assertThatThrownBy(() ->
                service.credit(7L, 100L, new BigDecimal("-500"), "Wrong sign", "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("crediting one customer against another customer's invoice is refused")
    void cannotCreditAcrossCustomers() {
        assertThatThrownBy(() ->
                service.credit(8L, 100L, new BigDecimal("500"), "Mistake", "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different customer");

        assertThat(storedNotes).isEmpty();
    }

    @Test
    @DisplayName("quote and credit note numbers cannot be mistaken for invoice numbers")
    void numbersSayWhatTheyAre() {
        ProformaInvoice quote = service.quote(7L, 1, null, null, null, "grace");
        CreditNote note = service.credit(7L, 100L, new BigDecimal("100"), "Test", "grace");

        assertThat(quote.getNumber()).contains("-PF-");
        assertThat(note.getNumber()).contains("-CN-");
        assertThat(quote.getNumber()).isNotEqualTo(note.getNumber());
    }
}
