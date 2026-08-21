package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Invoice;
import com.spalimited.hotspotbilling.domain.LedgerAdjustment;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.InvoiceRepository;
import com.spalimited.hotspotbilling.repository.LedgerAdjustmentRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The fast way of working out what everybody owes has to agree with the slow one.
 *
 * <p>The overview built a full statement for every subscriber and read the last
 * running total off the bottom of each — about fifteen thousand queries at five
 * thousand customers, and seven seconds on the screen every member of staff
 * opens first thing. It now asks the database for three grouped sums instead.
 *
 * <p>That is a change to how money is calculated, which is the kind of speed-up
 * that is only worth having if it is exactly equivalent. So the test that matters
 * here is not "is it fast" — it is that the two ways of arriving at a balance
 * agree, customer by customer, including the awkward ones.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerBalanceTest {

    @Mock private InvoiceRepository invoices;
    @Mock private SubscriptionPaymentRepository payments;
    @Mock private LedgerAdjustmentRepository adjustments;
    @Mock private SubscriberRepository subscribers;

    @InjectMocks
    private LedgerService ledger;

    private final Map<Long, List<Invoice>> invoiceStore = new LinkedHashMap<>();
    private final Map<Long, List<SubscriptionPayment>> paymentStore = new LinkedHashMap<>();
    private final Map<Long, List<LedgerAdjustment>> adjustmentStore = new LinkedHashMap<>();
    private final List<Subscriber> people = new ArrayList<>();

    @BeforeEach
    void setUp() {
        invoiceStore.clear();
        paymentStore.clear();
        adjustmentStore.clear();
        people.clear();

        when(invoices.findBySubscriberIdOrderByIssuedOnDesc(anyLong()))
                .thenAnswer(i -> invoiceStore.getOrDefault(i.getArgument(0), List.of()));
        when(payments.findBySubscriberIdOrderByCreatedAtDesc(anyLong()))
                .thenAnswer(i -> paymentStore.getOrDefault(i.getArgument(0), List.of()));
        when(adjustments.findBySubscriberIdOrderByAppliedOnAsc(anyLong()))
                .thenAnswer(i -> adjustmentStore.getOrDefault(i.getArgument(0), List.of()));
        when(subscribers.findAll()).thenReturn(people);

        // The grouped queries, answered from the same fixtures the per-customer
        // ones are. If the two disagree it is the code under test, not the data.
        when(invoices.totalInvoicedPerSubscriber()).thenAnswer(i -> {
            List<Object[]> rows = new ArrayList<>();
            invoiceStore.forEach((id, list) -> rows.add(new Object[] { id,
                    list.stream().filter(inv -> inv.getStatus() != Invoice.Status.CANCELLED)
                            .map(Invoice::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add) }));
            return rows;
        });
        when(payments.totalPaidPerSubscriber()).thenAnswer(i -> {
            List<Object[]> rows = new ArrayList<>();
            paymentStore.forEach((id, list) -> rows.add(new Object[] { id,
                    list.stream()
                            .filter(p -> p.getStatus() == SubscriptionPayment.Status.SUCCESS)
                            .map(SubscriptionPayment::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add) }));
            return rows;
        });
        when(adjustments.totalAdjustedPerSubscriberAndKind()).thenAnswer(i -> {
            List<Object[]> rows = new ArrayList<>();
            adjustmentStore.forEach((id, list) -> {
                Map<LedgerAdjustment.Kind, BigDecimal> byKind = new LinkedHashMap<>();
                for (LedgerAdjustment a : list) {
                    byKind.merge(a.getKind(), a.getAmount(), BigDecimal::add);
                }
                byKind.forEach((kind, total) -> rows.add(new Object[] { id, kind, total }));
            });
            return rows;
        });
    }

    private Subscriber person(long id) {
        Subscriber s = Subscriber.builder().id(id).fullName("Customer " + id)
                .phoneNumber("25471000000" + id).pppoeUsername("user" + id).build();
        people.add(s);
        return s;
    }

    private void invoice(long subscriberId, String amount, Invoice.Status status, int dayOfMonth) {
        invoiceStore.computeIfAbsent(subscriberId, k -> new ArrayList<>())
                .add(Invoice.builder()
                        .id((long) (invoiceStore.size() * 100 + dayOfMonth))
                        .subscriber(Subscriber.builder().id(subscriberId).build())
                        .number("INV-" + subscriberId + "-" + dayOfMonth)
                        .amount(new BigDecimal(amount))
                        .months(1)
                        .status(status)
                        .issuedOn(LocalDate.of(2026, 3, dayOfMonth))
                        .createdAt(Instant.now())
                        .build());
    }

    private void payment(long subscriberId, String amount, SubscriptionPayment.Status status,
                         int dayOfMonth) {
        paymentStore.computeIfAbsent(subscriberId, k -> new ArrayList<>())
                .add(SubscriptionPayment.builder()
                        .id((long) (paymentStore.size() * 100 + dayOfMonth))
                        .subscriber(Subscriber.builder().id(subscriberId).build())
                        .amount(new BigDecimal(amount))
                        .status(status)
                        .method(SubscriptionPayment.Method.MPESA)
                        .createdAt(LocalDate.of(2026, 3, dayOfMonth)
                                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
                        .build());
    }

    private void adjustment(long subscriberId, String amount, LedgerAdjustment.Kind kind,
                            int dayOfMonth) {
        adjustmentStore.computeIfAbsent(subscriberId, k -> new ArrayList<>())
                .add(LedgerAdjustment.builder()
                        .id((long) (adjustmentStore.size() * 100 + dayOfMonth))
                        .subscriber(Subscriber.builder().id(subscriberId).build())
                        .amount(new BigDecimal(amount))
                        .kind(kind)
                        .reason("test")
                        .appliedOn(LocalDate.of(2026, 3, dayOfMonth))
                        .createdAt(Instant.now())
                        .build());
    }

    /** The whole point: both routes, same answer, for everybody. */
    private void assertAgrees() {
        Map<Long, BigDecimal> fast = ledger.allBalances();
        for (Subscriber s : people) {
            BigDecimal slow = ledger.balance(s.getId());
            BigDecimal quick = fast.getOrDefault(s.getId(), BigDecimal.ZERO);
            assertThat(quick)
                    .as("customer %d: statement says %s, grouped sums say %s",
                            s.getId(), slow, quick)
                    .isEqualByComparingTo(slow);
        }
    }

    @Test
    @DisplayName("a customer who owes for one month agrees both ways")
    void simpleDebt() {
        person(1);
        invoice(1, "2500", Invoice.Status.UNPAID, 1);

        assertAgrees();
        assertThat(ledger.allBalances().get(1L)).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("invoiced and paid in full nets to nothing")
    void paidUp() {
        person(1);
        invoice(1, "2500", Invoice.Status.UNPAID, 1);
        payment(1, "2500", SubscriptionPayment.Status.SUCCESS, 5);

        assertAgrees();
        assertThat(ledger.allBalances().getOrDefault(1L, BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a cancelled invoice is not a debt, either way")
    void cancelledInvoicesAreIgnored() {
        person(1);
        invoice(1, "2500", Invoice.Status.UNPAID, 1);
        invoice(1, "9999", Invoice.Status.CANCELLED, 2);

        assertAgrees();
        assertThat(ledger.allBalances().get(1L)).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("a failed payment does not reduce what is owed, either way")
    void failedPaymentsAreIgnored() {
        person(1);
        invoice(1, "2500", Invoice.Status.UNPAID, 1);
        payment(1, "2500", SubscriptionPayment.Status.FAILED, 5);

        assertAgrees();
        assertThat(ledger.allBalances().get(1L)).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("a penalty adds and everything else reduces, the same both ways")
    void adjustmentsCarryTheirSign() {
        person(1);
        invoice(1, "2500", Invoice.Status.UNPAID, 1);
        adjustment(1, "500", LedgerAdjustment.Kind.PENALTY, 3);
        adjustment(1, "300", LedgerAdjustment.Kind.DISCOUNT, 4);
        adjustment(1, "200", LedgerAdjustment.Kind.CREDIT_NOTE, 5);
        adjustment(1, "100", LedgerAdjustment.Kind.WRITE_OFF, 6);

        // 2500 + 500 - 300 - 200 - 100
        assertAgrees();
        assertThat(ledger.allBalances().get(1L)).isEqualByComparingTo("2400");
    }

    @Test
    @DisplayName("several adjustments of one kind add up before the sign is applied")
    void repeatedAdjustmentsOfOneKind() {
        person(1);
        invoice(1, "5000", Invoice.Status.UNPAID, 1);
        adjustment(1, "200", LedgerAdjustment.Kind.DISCOUNT, 2);
        adjustment(1, "300", LedgerAdjustment.Kind.DISCOUNT, 3);

        // The grouped query returns one row per (customer, kind), so this is the
        // case where a sign applied in the wrong place would show up.
        assertAgrees();
        assertThat(ledger.allBalances().get(1L)).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("somebody in credit is in credit both ways")
    void overpaid() {
        person(1);
        invoice(1, "2500", Invoice.Status.UNPAID, 1);
        payment(1, "4000", SubscriptionPayment.Status.SUCCESS, 5);

        assertAgrees();
        assertThat(ledger.allBalances().get(1L)).isEqualByComparingTo("-1500");
    }

    @Test
    @DisplayName("a book full of different situations agrees customer by customer")
    void aWholeBookAgrees() {
        person(1);
        person(2);
        person(3);
        person(4);
        // Owes for two months, paid one.
        invoice(1, "2500", Invoice.Status.UNPAID, 1);
        invoice(1, "2500", Invoice.Status.UNPAID, 15);
        payment(1, "2500", SubscriptionPayment.Status.SUCCESS, 16);
        // Paid up, with a discount that put them in credit.
        invoice(2, "3000", Invoice.Status.PAID, 1);
        payment(2, "3000", SubscriptionPayment.Status.SUCCESS, 2);
        adjustment(2, "500", LedgerAdjustment.Kind.DISCOUNT, 3);
        // Nothing at all — the case that has to come out as zero rather than
        // missing from the map in a way the caller trips over.
        person(5);
        // Adjustments only, no invoice.
        adjustment(4, "750", LedgerAdjustment.Kind.PENALTY, 8);
        // Cancelled invoice and a failed payment: both ignored.
        invoice(3, "1200", Invoice.Status.CANCELLED, 4);
        payment(3, "1200", SubscriptionPayment.Status.FAILED, 5);

        assertAgrees();
        Map<Long, BigDecimal> fast = ledger.allBalances();
        assertThat(fast.get(1L)).isEqualByComparingTo("2500");
        assertThat(fast.get(2L)).isEqualByComparingTo("-500");
        assertThat(fast.getOrDefault(3L, BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(fast.get(4L)).isEqualByComparingTo("750");
        assertThat(fast.getOrDefault(5L, BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("only the people who owe or are owed appear in the outstanding list")
    void outstandingSkipsSettledCustomers() {
        person(1);
        person(2);
        invoice(1, "2500", Invoice.Status.UNPAID, 1);
        invoice(2, "2500", Invoice.Status.PAID, 1);
        payment(2, "2500", SubscriptionPayment.Status.SUCCESS, 2);

        List<Map<String, Object>> owing = ledger.outstanding();

        assertThat(owing).hasSize(1);
        assertThat(owing.get(0).get("subscriberId")).isEqualTo(1L);
        assertThat(owing.get(0).get("owes")).isEqualTo(true);
    }
}
