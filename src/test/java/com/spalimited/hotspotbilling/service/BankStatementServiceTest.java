package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.BankImport;
import com.spalimited.hotspotbilling.domain.BankTransaction;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SubscriptionPayment;
import com.spalimited.hotspotbilling.repository.BankImportRepository;
import com.spalimited.hotspotbilling.repository.BankTransactionRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Matching bank transfers to customers.
 *
 * <p>Two things here are worth more than all the rest. The first is that a
 * statement uploaded twice does not credit everybody twice -- operators
 * re-download overlapping date ranges as a matter of routine, so this is not an
 * edge case but the normal way the feature gets used. The second is that a guess
 * stays a guess: only a transfer quoting our own reference is applied without a
 * person, because crediting the wrong customer costs two people an afternoon and
 * leaves the customer who actually paid still cut off.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankStatementServiceTest {

    @Mock
    private BankImportRepository imports;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private SubscriberRepository subscribers;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private PhoneNumbers phoneNumbers;

    @Mock
    private AuditService audit;

    @InjectMocks
    private BankStatementService service;

    private final List<BankTransaction> stored = new ArrayList<>();
    private final Set<String> seenKeys = new HashSet<>();
    private List<Subscriber> everyone;

    @BeforeEach
    void setUp() {
        stored.clear();
        seenKeys.clear();

        Subscriber mary = Subscriber.builder().id(42L).fullName("Mary Kamau")
                .phoneNumber("254712345678").pppoeUsername("mkamau")
                .monthlyFee(new BigDecimal("2500")).build();
        Subscriber john = Subscriber.builder().id(43L).fullName("John Otieno")
                .phoneNumber("254720000000").pppoeUsername("jotieno")
                .monthlyFee(new BigDecimal("1500")).build();
        everyone = List.of(mary, john);

        when(subscribers.findAll()).thenReturn(everyone);
        when(subscribers.existsById(anyLong())).thenAnswer(i ->
                everyone.stream().anyMatch(s -> s.getId().equals(i.getArgument(0))));
        when(subscribers.findById(anyLong())).thenAnswer(i ->
                everyone.stream().filter(s -> s.getId().equals(i.getArgument(0))).findFirst());

        when(imports.save(any())).thenAnswer(i -> {
            BankImport b = i.getArgument(0);
            if (b.getId() == null) {
                b.setId(1L);
            }
            return b;
        });
        when(imports.findById(anyLong())).thenAnswer(i -> Optional.of(BankImport.builder()
                .id(1L).filename("x.csv").uploadedAt(java.time.Instant.now()).build()));

        when(transactions.save(any())).thenAnswer(i -> {
            BankTransaction t = i.getArgument(0);
            if (t.getId() == null) {
                t.setId((long) (stored.size() + 1));
                stored.add(t);
                seenKeys.add(t.getDedupeKey());
            }
            return t;
        });
        when(transactions.existsByDedupeKey(anyString()))
                .thenAnswer(i -> seenKeys.contains(i.getArgument(0)));
        when(transactions.findById(anyLong())).thenAnswer(i ->
                stored.stream().filter(t -> t.getId().equals(i.getArgument(0))).findFirst());

        // Phone normalisation, as the real one behaves for Kenya: a leading zero
        // becomes 254.
        when(phoneNumbers.normalise(anyString())).thenAnswer(i -> {
            String raw = i.getArgument(0);
            if (raw == null) {
                return null;
            }
            String digits = raw.replaceAll("\\D", "");
            if (digits.startsWith("0") && digits.length() == 10) {
                return "254" + digits.substring(1);
            }
            return digits;
        });

        when(subscriptionService.creditBankTransfer(anyLong(), anyInt(), any(), anyString()))
                .thenAnswer(i -> SubscriptionPayment.builder().id(99L).build());
    }

    private BankStatementService.Row row(String date, String narration, String ref, String amount) {
        return new BankStatementService.Row(LocalDate.parse(date), narration, ref,
                new BigDecimal(amount));
    }

    private Map<String, Object> ingest(BankStatementService.Row... rows) {
        return service.ingest("statement.csv", "Equity", List.of(rows), "grace");
    }

    // --- the automatic case ---

    @Test
    @DisplayName("a transfer quoting our own reference is credited without asking")
    void ourReferenceIsApplied() {
        Map<String, Object> result = ingest(
                row("2026-08-01", "RTGS TRANSFER PPPOE-42-1093 MARY K", "FT2201", "2500"));

        assertThat(result.get("applied")).isEqualTo(1);
        verify(subscriptionService).creditBankTransfer(eq(42L), eq(1), eq(new BigDecimal("2500")),
                eq("BANK FT2201"));
        assertThat(stored.get(0).getStatus()).isEqualTo(BankTransaction.Status.APPLIED);
    }

    @Test
    @DisplayName("our reference for a customer who no longer exists is not applied to anybody")
    void referenceToDeletedCustomer() {
        Map<String, Object> result = ingest(
                row("2026-08-01", "TRANSFER PPPOE-999-1", "FT1", "2500"));

        assertThat(result.get("applied")).isEqualTo(0);
        assertThat(stored.get(0).getStatus()).isEqualTo(BankTransaction.Status.UNMATCHED);
        verify(subscriptionService, never()).creditBankTransfer(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("a part payment is never auto-applied, even with our own reference on it")
    void partPaymentWaits() {
        // 500 against a 2,500 plan. Rounding this up to a month would give away
        // 2,000 shillings and would look in the record like they paid in full.
        Map<String, Object> result = ingest(
                row("2026-08-01", "TRANSFER PPPOE-42-1093", "FT1", "500"));

        assertThat(result.get("applied")).isEqualTo(0);
        assertThat(stored.get(0).getStatus()).isEqualTo(BankTransaction.Status.MATCHED);
        assertThat(stored.get(0).getSubscriberId()).isEqualTo(42L);
        verify(subscriptionService, never()).creditBankTransfer(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("an overpayment buys whole months and no more")
    void overpaymentBuysWholeMonths() {
        ingest(row("2026-08-01", "TRANSFER PPPOE-42-7", "FT1", "6000"));

        // 6000 / 2500 = 2 months and 1000 left over, not 2.4 months.
        verify(subscriptionService).creditBankTransfer(eq(42L), eq(2), any(), anyString());
    }

    // --- guesses that stay guesses ---

    @Test
    @DisplayName("a phone number in the narration is offered, not applied")
    void phoneIsOnlyASuggestion() {
        Map<String, Object> result = ingest(
                row("2026-08-01", "MPESA C2B 0712345678 SOMEBODY", "FT2", "2500"));

        assertThat(result.get("matched")).isEqualTo(1);
        assertThat(result.get("applied")).isEqualTo(0);
        BankTransaction t = stored.get(0);
        assertThat(t.getStatus()).isEqualTo(BankTransaction.Status.MATCHED);
        assertThat(t.getSubscriberId()).isEqualTo(42L);
        // The reason is shown to whoever confirms it, so it has to be something
        // a person can check rather than a score.
        assertThat(t.getMatchReason()).contains("phone number");
        verify(subscriptionService, never()).creditBankTransfer(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("a username in the narration is offered")
    void usernameIsASuggestion() {
        ingest(row("2026-08-01", "TRANSFER FROM MKAMAU", "FT3", "2500"));

        assertThat(stored.get(0).getSubscriberId()).isEqualTo(42L);
        assertThat(stored.get(0).getMatchReason()).contains("username");
    }

    @Test
    @DisplayName("a username inside a longer word is not a match")
    void usernameNeedsWordBoundaries() {
        // "jotieno" happens to sit inside this. Matching it would credit the
        // wrong customer on the strength of a coincidence.
        ingest(row("2026-08-01", "PAYMENT REF XXJOTIENOXX", "FT4", "1500"));

        assertThat(stored.get(0).getStatus()).isEqualTo(BankTransaction.Status.UNMATCHED);
        assertThat(stored.get(0).getSubscriberId()).isNull();
    }

    @Test
    @DisplayName("a name in the narration is offered")
    void nameIsASuggestion() {
        ingest(row("2026-08-01", "EFT CREDIT JOHN OTIENO NAIROBI", "FT5", "1500"));

        assertThat(stored.get(0).getSubscriberId()).isEqualTo(43L);
        assertThat(stored.get(0).getMatchReason()).contains("name");
    }

    @Test
    @DisplayName("a narration nobody can be picked out of is left alone")
    void unmatchedStaysUnmatched() {
        Map<String, Object> result = ingest(
                row("2026-08-01", "CHEQUE DEPOSIT 004311", "FT6", "9000"));

        assertThat(result.get("matched")).isEqualTo(0);
        assertThat(stored.get(0).getStatus()).isEqualTo(BankTransaction.Status.UNMATCHED);
    }

    // --- the same statement twice ---

    @Test
    @DisplayName("the same statement uploaded twice credits nobody twice")
    void duplicatesAreRecognised() {
        BankStatementService.Row a = row("2026-08-01", "TRANSFER PPPOE-42-1093", "FT2201", "2500");
        BankStatementService.Row b = row("2026-08-02", "EFT CREDIT JOHN OTIENO", "FT2202", "1500");

        Map<String, Object> first = ingest(a, b);
        assertThat(first.get("credits")).isEqualTo(2);
        assertThat(first.get("duplicates")).isEqualTo(0);
        assertThat(stored).hasSize(2);

        Map<String, Object> second = ingest(a, b);
        assertThat(second.get("duplicates")).isEqualTo(2);
        // Nothing new stored, and crucially no second credit to Mary.
        assertThat(stored).hasSize(2);
        verify(subscriptionService, org.mockito.Mockito.times(1))
                .creditBankTransfer(eq(42L), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("two genuinely different transfers that look similar are both kept")
    void similarButDistinct() {
        // Same customer, same amount, same day, different bank reference. This is
        // somebody paying for two lines and both must land.
        ingest(row("2026-08-01", "TRANSFER FROM MARY KAMAU", "FT100", "2500"),
                row("2026-08-01", "TRANSFER FROM MARY KAMAU", "FT101", "2500"));

        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("the dedupe key ignores whitespace and case but not the reference")
    void dedupeKeyShape() {
        BankStatementService.Row tidy = row("2026-08-01", "TRANSFER FROM MARY", "FT1", "2500");
        BankStatementService.Row messy = row("2026-08-01", "  transfer   from   mary ", "ft1", "2500.00");
        BankStatementService.Row other = row("2026-08-01", "TRANSFER FROM MARY", "FT2", "2500");

        assertThat(BankStatementService.dedupeKey(tidy))
                .isEqualTo(BankStatementService.dedupeKey(messy));
        assertThat(BankStatementService.dedupeKey(tidy))
                .isNotEqualTo(BankStatementService.dedupeKey(other));
    }

    // --- debits, and applying by hand ---

    @Test
    @DisplayName("debits are dropped rather than queued")
    void debitsAreNotWork() {
        Map<String, Object> result = ingest(
                row("2026-08-01", "SALARY PAYMENT", "FT7", "-45000"),
                row("2026-08-01", "TRANSFER FROM MARY KAMAU", "FT8", "2500"));

        assertThat(result.get("credits")).isEqualTo(1);
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("a person can correct a match, and the correction is what gets credited")
    void manualOverride() {
        ingest(row("2026-08-01", "EFT CREDIT JOHN OTIENO", "FT9", "2500"));
        assertThat(stored.get(0).getSubscriberId()).isEqualTo(43L);

        // It was actually Mary paying, and Grace can see that from the slip.
        Map<String, Object> result = service.apply(stored.get(0).getId(), 42L, "grace");

        assertThat(result.get("ok")).isEqualTo(true);
        verify(subscriptionService).creditBankTransfer(eq(42L), eq(1), any(), anyString());
        assertThat(stored.get(0).getSubscriberId()).isEqualTo(42L);
        assertThat(stored.get(0).getDecidedBy()).isEqualTo("grace");
    }

    @Test
    @DisplayName("applying the same transaction twice credits once")
    void applyIsIdempotent() {
        ingest(row("2026-08-01", "EFT CREDIT JOHN OTIENO", "FT10", "1500"));
        service.apply(stored.get(0).getId(), null, "grace");

        Map<String, Object> again = service.apply(stored.get(0).getId(), null, "grace");

        assertThat(again.get("ok")).isEqualTo(false);
        verify(subscriptionService, org.mockito.Mockito.times(1))
                .creditBankTransfer(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("something already credited cannot be set aside")
    void cannotIgnoreWhatWasApplied() {
        ingest(row("2026-08-01", "TRANSFER PPPOE-42-1", "FT11", "2500"));

        Map<String, Object> result = service.ignore(stored.get(0).getId(), "grace");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(stored.get(0).getStatus()).isEqualTo(BankTransaction.Status.APPLIED);
    }

    @Test
    @DisplayName("one phone number shared by two customers is not guessed at")
    void ambiguousPhoneIsNoMatch() {
        Subscriber twin = Subscriber.builder().id(44L).fullName("Household Second Line")
                .phoneNumber("254712345678").pppoeUsername("second")
                .monthlyFee(new BigDecimal("2500")).build();
        List<Subscriber> withTwin = new ArrayList<>(everyone);
        withTwin.add(twin);
        when(subscribers.findAll()).thenReturn(withTwin);

        ingest(row("2026-08-01", "MPESA C2B 0712345678", "FT12", "2500"));

        // A household with two lines on one number. Picking one would be a coin
        // toss with somebody's connection on it.
        assertThat(stored.get(0).getStatus()).isEqualTo(BankTransaction.Status.UNMATCHED);
    }
}
