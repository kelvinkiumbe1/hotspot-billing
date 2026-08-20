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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Moving an ISP's book across.
 *
 * <p>Most of these are about refusing to guess. An import that quietly gets a
 * date the wrong way round cuts off paying customers a month early, or gives a
 * month away — and it does it three thousand times before anybody notices. So
 * the tests that matter are the ones that assert nothing was invented: an
 * ambiguous date stays empty, an unrecognised number stays empty, and a login
 * that already belongs to somebody is never overwritten.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MigrationImportServiceTest {

    @Mock private MigrationBatchRepository batches;
    @Mock private MigrationRowRepository rows;
    @Mock private SubscriberRepository subscribers;
    @Mock private PlanRepository plans;
    @Mock private PhoneNumbers phoneNumbers;
    @Mock private AuditService audit;

    private MigrationRowPromoter promoter;

    private MigrationImportService service;

    private final Map<Long, MigrationBatch> batchStore = new LinkedHashMap<>();
    private final List<MigrationRow> rowStore = new ArrayList<>();
    private final List<Subscriber> subscriberStore = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @BeforeEach
    void setUp() {
        batchStore.clear();
        rowStore.clear();
        subscriberStore.clear();

        when(batches.save(any())).thenAnswer(i -> {
            MigrationBatch b = i.getArgument(0);
            if (b.getId() == null) {
                b.setId(ids.getAndIncrement());
            }
            batchStore.put(b.getId(), b);
            return b;
        });
        when(batches.findById(anyLong())).thenAnswer(i ->
                Optional.ofNullable(batchStore.get(i.getArgument(0))));
        when(rows.save(any())).thenAnswer(i -> {
            MigrationRow r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId(ids.getAndIncrement());
                rowStore.add(r);
            }
            return r;
        });
        when(rows.findByBatchIdOrderByIdAsc(anyLong())).thenAnswer(i ->
                rowStore.stream().filter(r -> i.getArgument(0).equals(r.getBatchId())).toList());
        when(rows.findByBatchIdAndVerdictOrderByIdAsc(anyLong(), any())).thenAnswer(i ->
                rowStore.stream()
                        .filter(r -> i.getArgument(0).equals(r.getBatchId()))
                        .filter(r -> r.getVerdict() == i.getArgument(1))
                        .toList());
        when(rows.countByBatchIdAndVerdict(anyLong(), any())).thenAnswer(i ->
                rowStore.stream()
                        .filter(r -> i.getArgument(0).equals(r.getBatchId()))
                        .filter(r -> r.getVerdict() == i.getArgument(1))
                        .count());

        when(subscribers.findAll()).thenReturn(subscriberStore);
        when(subscribers.save(any())).thenAnswer(i -> {
            Subscriber s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(ids.getAndIncrement());
                subscriberStore.add(s);
            }
            return s;
        });
        when(subscribers.findByPppoeUsername(anyString())).thenAnswer(i ->
                subscriberStore.stream()
                        .filter(s -> i.getArgument(0).equals(s.getPppoeUsername()))
                        .findFirst());

        when(plans.findAll()).thenReturn(List.of(
                Plan.builder().id(10L).name("Home 10Mbps").price(new BigDecimal("2500"))
                        .bandwidth("10M/10M").build(),
                Plan.builder().id(11L).name("Home 20Mbps").price(new BigDecimal("4000"))
                        .bandwidth("20M/20M").build()));

        // A real promoter over the mocked repositories: the isolation it provides
        // is a transaction property and cannot be asserted here at all — that is
        // proven against a real database instead. What these tests check is the
        // decisions, and for those the real promoter is the honest collaborator.
        promoter = new MigrationRowPromoter(subscribers, rows);
        service = new MigrationImportService(batches, rows, subscribers, plans,
                phoneNumbers, audit, promoter);

        when(phoneNumbers.normalise(anyString())).thenAnswer(i -> {
            String raw = i.getArgument(0);
            if (raw == null) {
                return null;
            }
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.startsWith("0") && digits.length() == 10) {
                return "254" + digits.substring(1);
            }
            return digits.isBlank() ? null : digits;
        });
    }

    private static Map<String, String> row(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private MigrationRow only() {
        assertThat(rowStore).hasSize(1);
        return rowStore.get(0);
    }

    // --- what each system calls things ---

    @Test
    @DisplayName("a Splynx services export is understood")
    void splynxHeadings() {
        service.stage(MigrationSource.SPLYNX, "book", null, List.of(row(
                "customer_id", "8812", "customer_name", "Mary Kamau", "phone", "0712345678",
                "login", "mkamau", "password", "secret12", "tariff", "Home 10Mbps",
                "unit_price", "2,500.00", "ipv4", "41.90.64.12", "status", "active",
                "end_date", "2026-09-30")), "grace");

        MigrationRow r = only();
        assertThat(r.getExternalId()).isEqualTo("8812");
        assertThat(r.getFullName()).isEqualTo("Mary Kamau");
        assertThat(r.getPhoneNumber()).isEqualTo("254712345678");
        assertThat(r.getPppoeUsername()).isEqualTo("mkamau");
        assertThat(r.getPlanName()).isEqualTo("Home 10Mbps");
        assertThat(r.getMonthlyPrice()).isEqualByComparingTo("2500");
        assertThat(r.getStaticIp()).isEqualTo("41.90.64.12");
        assertThat(r.getMatchedPlanId()).isEqualTo(10L);
        assertThat(r.getVerdict()).isEqualTo(MigrationRow.Verdict.NEW);
    }

    @Test
    @DisplayName("UISP splits a person's name across two columns, and it is put back together")
    void uispAssemblesTheName() {
        service.stage(MigrationSource.UISP, null, null, List.of(row(
                "clientId", "441", "firstName", "John", "lastName", "Otieno",
                "phone", "0722000111", "servicePlanName", "Home 20Mbps",
                "price", "4000", "pppoeUsername", "jotieno")), "grace");

        assertThat(only().getFullName()).isEqualTo("John Otieno");
    }

    @Test
    @DisplayName("a UISP business account is filed under the company, not the person who signed")
    void companyBeatsPerson() {
        service.stage(MigrationSource.UISP, null, null, List.of(row(
                "clientId", "9", "companyName", "Acme Ltd", "firstName", "John",
                "lastName", "Otieno", "phone", "0722000111", "pppoeUsername", "acme")), "grace");

        // Invoicing a business in the name of its director is the sort of small
        // wrongness an operator ends up apologising for.
        assertThat(only().getFullName()).isEqualTo("Acme Ltd");
    }

    @Test
    @DisplayName("headings are matched however they were punctuated")
    void headingsAreNormalised() {
        service.stage(MigrationSource.UISP, null, null, List.of(row(
                "Client ID", "7", "First Name", "Ann", "Last Name", "Wanjiru",
                "PPPoE Username", "awanjiru", "Service Plan Name", "Home 10Mbps",
                "Phone", "0733111222")), "grace");

        MigrationRow r = only();
        assertThat(r.getExternalId()).isEqualTo("7");
        assertThat(r.getFullName()).isEqualTo("Ann Wanjiru");
        assertThat(r.getPppoeUsername()).isEqualTo("awanjiru");
        assertThat(r.getMatchedPlanId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("a Radius Manager export carries the expiry, which is what decides who is on")
    void radiusManagerExpiry() {
        service.stage(MigrationSource.RADIUS_MANAGER, null, null, List.of(row(
                "username", "kiumbe01", "password", "pass1234", "firstname", "Peter",
                "lastname", "Mwangi", "mobile", "0700111222", "srvname", "Home 10Mbps",
                "expiration", "2026-12-31")), "grace");

        assertThat(only().getPaidUntil())
                .isEqualTo(LocalDate.of(2026, 12, 31).atStartOfDay(ZONE).toInstant());
    }

    @Test
    @DisplayName("a tidied-up export still works, whatever it was exported from")
    void fallsBackToGenericHeadings() {
        // An operator who exported from Splynx and then renamed the columns by
        // hand should not be told their file is unreadable.
        service.stage(MigrationSource.SPLYNX, null, null, List.of(row(
                "name", "Grace Njeri", "mobile", "0711222333", "package", "Home 20Mbps",
                "monthly_fee", "4000", "pppoe", "gnjeri")), "grace");

        MigrationRow r = only();
        assertThat(r.getFullName()).isEqualTo("Grace Njeri");
        assertThat(r.getPppoeUsername()).isEqualTo("gnjeri");
        assertThat(r.getMatchedPlanId()).isEqualTo(11L);
    }

    // --- money ---

    @Test
    @DisplayName("prices are read however the old system wrote them")
    void moneyFormats() {
        assertThat(MigrationImportService.money("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(MigrationImportService.money("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(MigrationImportService.money("KES 2,500")).isEqualByComparingTo("2500");
        assertThat(MigrationImportService.money("2500")).isEqualByComparingTo("2500");
        assertThat(MigrationImportService.money("2.500")).isEqualByComparingTo("2500");
        assertThat(MigrationImportService.money("2,50")).isEqualByComparingTo("2.50");
        assertThat(MigrationImportService.money("2.50")).isEqualByComparingTo("2.50");
        assertThat(MigrationImportService.money("(500)")).isEqualByComparingTo("-500");
        assertThat(MigrationImportService.money("-500")).isEqualByComparingTo("-500");
    }

    @Test
    @DisplayName("something that is not a number stays empty rather than becoming zero")
    void unreadableMoneyIsNotZero() {
        // Zero is a claim about what somebody owes. A wrong one is a phone call.
        assertThat(MigrationImportService.money("n/a")).isNull();
        assertThat(MigrationImportService.money("")).isNull();
        assertThat(MigrationImportService.money(null)).isNull();
    }

    // --- dates, the expensive field ---

    @Test
    @DisplayName("an ISO date is taken at face value")
    void isoDates() {
        assertThat(MigrationImportService.date("2026-09-30", MigrationImportService.DateOrder.AUTO))
                .isEqualTo(LocalDate.of(2026, 9, 30).atStartOfDay(ZONE).toInstant());
        assertThat(MigrationImportService.date("2026-09-30 14:30:00",
                MigrationImportService.DateOrder.AUTO)).isNotNull();
    }

    @Test
    @DisplayName("a date that can only be read one way is read that way")
    void unambiguousSlashDate() {
        // 25 cannot be a month.
        assertThat(MigrationImportService.date("25/12/2026", MigrationImportService.DateOrder.AUTO))
                .isEqualTo(LocalDate.of(2026, 12, 25).atStartOfDay(ZONE).toInstant());
        assertThat(MigrationImportService.date("12/25/2026", MigrationImportService.DateOrder.AUTO))
                .isEqualTo(LocalDate.of(2026, 12, 25).atStartOfDay(ZONE).toInstant());
    }

    @Test
    @DisplayName("an ambiguous date is refused rather than guessed")
    void ambiguousDateIsRefused() {
        // 03/04/2026 is the 3rd of April or the 4th of March. Guessing wrong cuts
        // a paying customer off a month early, or gives a month away -- and does
        // it silently, to everybody in the file at once.
        assertThat(MigrationImportService.date("03/04/2026", MigrationImportService.DateOrder.AUTO))
                .isNull();
    }

    @Test
    @DisplayName("once the operator says which order, an ambiguous date is read")
    void declaredOrderResolvesIt() {
        assertThat(MigrationImportService.date("03/04/2026", MigrationImportService.DateOrder.DMY))
                .isEqualTo(LocalDate.of(2026, 4, 3).atStartOfDay(ZONE).toInstant());
        assertThat(MigrationImportService.date("03/04/2026", MigrationImportService.DateOrder.MDY))
                .isEqualTo(LocalDate.of(2026, 3, 4).atStartOfDay(ZONE).toInstant());
    }

    @Test
    @DisplayName("an unreadable expiry is called out, not left for the operator to find")
    void unreadableDatesAreWarnedAbout() {
        MigrationImportService.Staged staged = service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "Mary", "pppoe", "mary", "expires", "03/04/2026")), "grace");

        assertThat(staged.warnings()).anyMatch(w -> w.contains("could not be read"));
    }

    // --- addresses ---

    @Test
    @DisplayName("an IP range gives up its first address")
    void ipRanges() {
        assertThat(MigrationImportService.firstAddress("41.90.64.12/30")).isEqualTo("41.90.64.12");
        assertThat(MigrationImportService.firstAddress("41.90.64.12, 41.90.64.13"))
                .isEqualTo("41.90.64.12");
        assertThat(MigrationImportService.firstAddress("not-an-address")).isNull();
    }

    // --- verdicts ---

    @Test
    @DisplayName("a row with no name cannot become anybody")
    void namelessRowIsIncomplete() {
        service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("pppoe", "ghost", "package", "Home 10Mbps")), "grace");

        assertThat(only().getVerdict()).isEqualTo(MigrationRow.Verdict.INCOMPLETE);
        assertThat(only().getVerdictNote()).contains("No name");
    }

    @Test
    @DisplayName("a row with neither a login nor a phone number cannot be connected or reached")
    void unreachableRowIsIncomplete() {
        service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "Nobody", "package", "Home 10Mbps")), "grace");

        assertThat(only().getVerdict()).isEqualTo(MigrationRow.Verdict.INCOMPLETE);
    }

    @Test
    @DisplayName("a login that already belongs to somebody here is skipped, never overwritten")
    void existingLoginCollides() {
        subscriberStore.add(Subscriber.builder().id(99L).fullName("Existing Person")
                .pppoeUsername("mkamau").build());

        service.stage(MigrationSource.SPLYNX, null, null, List.of(row(
                "customer_name", "Mary Kamau", "login", "mkamau", "phone", "0712345678",
                "tariff", "Home 10Mbps")), "grace");

        MigrationRow r = only();
        assertThat(r.getVerdict()).isEqualTo(MigrationRow.Verdict.COLLISION);
        assertThat(r.getVerdictNote()).contains("Existing Person", "not overwritten");
        assertThat(r.getSubscriberId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("uploading the same export twice does not double the book")
    void secondUploadCollidesWithTheFirst() {
        List<Map<String, String>> file = List.of(row(
                "customer_name", "Mary Kamau", "login", "mkamau", "phone", "0712345678",
                "tariff", "Home 10Mbps", "unit_price", "2500", "end_date", "2026-12-31"));

        Long first = service.stage(MigrationSource.SPLYNX, "first", null, file, "grace").batchId();
        service.promote(first, "grace");
        rowStore.clear();

        MigrationImportService.Staged again =
                service.stage(MigrationSource.SPLYNX, "again", null, file, "grace");

        // An operator uploads the same file twice far more often than they admit.
        assertThat(again.collisions()).isEqualTo(1);
        assertThat(again.ready()).isZero();
    }

    @Test
    @DisplayName("a package that does not exist here is named, so it can be created first")
    void unknownPackageIsNamed() {
        MigrationImportService.Staged staged = service.stage(MigrationSource.SPLYNX, null, null,
                List.of(row("customer_name", "Mary", "login", "mary", "phone", "0712000004",
                        "tariff", "Fibre 100Mbps", "unit_price", "9000")), "grace");

        assertThat(staged.warnings()).anyMatch(w -> w.contains("does not exist here"));
        assertThat(service.plan(staged.batchId()).get("packagesNotHere"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("Fibre 100Mbps");
    }

    // --- the parallel run ---

    @Test
    @DisplayName("the comparison shows both monthly totals and what changes")
    void comparisonShowsTheMoney() {
        Long batch = service.stage(MigrationSource.SPLYNX, null, null, List.of(
                // Same price as ours: nothing changes for this one.
                row("customer_name", "Mary", "login", "mary", "phone", "0712000001",
                        "tariff", "Home 10Mbps", "unit_price", "2500"),
                // Theirs is cheaper than our matching package.
                row("customer_name", "John", "login", "john", "phone", "0712000002",
                        "tariff", "Home 20Mbps", "unit_price", "3500")), "grace").batchId();

        Map<String, Object> compare = service.compare(batch);

        assertThat(compare.get("compared")).isEqualTo(2);
        assertThat(compare.get("unchanged")).isEqualTo(1);
        assertThat((BigDecimal) compare.get("theirMonthlyTotal")).isEqualByComparingTo("6000");
        assertThat((BigDecimal) compare.get("ourMonthlyTotal")).isEqualByComparingTo("6500");
        // The number an operator actually asks for: what the move does to the bill.
        assertThat((BigDecimal) compare.get("difference")).isEqualByComparingTo("500");
        assertThat(compare.get("differenceCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("with no package matched, their own price carries over rather than vanishing")
    void unmatchedPackageKeepsTheirPrice() {
        Long batch = service.stage(MigrationSource.SPLYNX, null, null, List.of(
                row("customer_name", "Mary", "login", "mary", "phone", "0712000003",
                        "tariff", "Fibre 100Mbps", "unit_price", "9000")), "grace").batchId();

        Map<String, Object> compare = service.compare(batch);

        assertThat((BigDecimal) compare.get("ourMonthlyTotal")).isEqualByComparingTo("9000");
        assertThat((BigDecimal) compare.get("difference")).isEqualByComparingTo("0");
    }

    // --- the cutover ---

    @Test
    @DisplayName("promoting creates the customers and records where they came from")
    void promoteCreatesSubscribers() {
        Long batch = service.stage(MigrationSource.SPLYNX, null, null, List.of(row(
                "customer_id", "8812", "customer_name", "Mary Kamau", "login", "mkamau",
                "password", "secret12", "phone", "0712345678", "tariff", "Home 10Mbps",
                "unit_price", "2500", "end_date", "2099-12-31")), "grace").batchId();

        MigrationImportService.Promoted result = service.promote(batch, "grace");

        assertThat(result.created()).isEqualTo(1);
        Subscriber made = subscriberStore.get(0);
        assertThat(made.getFullName()).isEqualTo("Mary Kamau");
        assertThat(made.getPppoeUsername()).isEqualTo("mkamau");
        assertThat(made.getBandwidth()).isEqualTo("10M/10M");
        assertThat(made.getMonthlyFee()).isEqualByComparingTo("2500");
        // Traceable afterwards, which is the difference between answering a
        // support call and guessing at it.
        assertThat(made.getMigratedFrom()).isEqualTo("SPLYNX");
        assertThat(made.getMigratedRef()).isEqualTo("8812");
        assertThat(made.getMigratedAt()).isNotNull();
    }

    @Test
    @DisplayName("somebody paid up arrives switched on; somebody who is not does not")
    void statusFollowsWhetherTheyArePaidUp() {
        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
        Instant past = Instant.now().minus(30, ChronoUnit.DAYS);
        Long batch = service.stage(MigrationSource.GENERIC, null, null, List.of(
                row("name", "Paid Up", "pppoe", "paid", "phone", "0712000010", "price", "2500",
                        "expires", future.toString()),
                row("name", "In Arrears", "pppoe", "arrears", "phone", "0712000011",
                        "price", "2500", "expires", past.toString())),
                "grace").batchId();

        service.promote(batch, "grace");

        Subscriber paid = subscriberStore.stream()
                .filter(s -> "paid".equals(s.getPppoeUsername())).findFirst().orElseThrow();
        Subscriber arrears = subscriberStore.stream()
                .filter(s -> "arrears".equals(s.getPppoeUsername())).findFirst().orElseThrow();

        assertThat(paid.getStatus()).isEqualTo(Subscriber.Status.ACTIVE);
        // Switching on somebody who has not paid is a gift the operator never
        // agreed to make -- and at three thousand rows it is a large one.
        assertThat(arrears.getStatus()).isEqualTo(Subscriber.Status.SUSPENDED);
    }

    @Test
    @DisplayName("a customer with a static address comes across as a static customer")
    void staticCustomersKeepTheirShape() {
        Long batch = service.stage(MigrationSource.SPLYNX, null, null, List.of(row(
                "customer_name", "Acme Ltd", "login", "acme", "phone", "0712000012",
                "ipv4", "41.90.64.12/30", "tariff", "Home 20Mbps")), "grace").batchId();

        service.promote(batch, "grace");

        Subscriber made = subscriberStore.get(0);
        assertThat(made.getConnectionType()).isEqualTo(Subscriber.ConnectionType.STATIC);
        assertThat(made.getStaticIp()).isEqualTo("41.90.64.12");
    }

    @Test
    @DisplayName("collisions and incomplete rows are left behind, not forced through")
    void promoteOnlyTakesTheReadyRows() {
        subscriberStore.add(Subscriber.builder().id(99L).fullName("Existing").pppoeUsername("taken").build());
        Long batch = service.stage(MigrationSource.GENERIC, null, null, List.of(
                row("name", "Fine", "pppoe", "fine", "phone", "0712000005", "price", "2500",
                        "expires", "2099-12-31"),
                row("name", "Clash", "pppoe", "taken", "phone", "0712000006", "price", "2500"),
                row("pppoe", "nameless")), "grace").batchId();

        MigrationImportService.Promoted result = service.promote(batch, "grace");

        assertThat(result.created()).isEqualTo(1);
        assertThat(subscriberStore).hasSize(2);
    }

    @Test
    @DisplayName("a failing row is counted and named, and the others are still created")
    void aFailingRowIsReportedAndTheRestProceed() {
        Long batch = service.stage(MigrationSource.GENERIC, null, null, List.of(
                row("name", "First", "pppoe", "first", "phone", "0712000007", "price", "2500",
                        "expires", "2099-12-31"),
                row("name", "Explodes", "pppoe", "boom", "phone", "0712000008", "price", "2500",
                        "expires", "2099-12-31"),
                row("name", "Third", "pppoe", "third", "phone", "0712000009", "price", "2500",
                        "expires", "2099-12-31")), "grace").batchId();
        org.mockito.Mockito.doAnswer(i -> {
            Subscriber s = i.getArgument(0);
            if ("boom".equals(s.getPppoeUsername())) {
                throw new RuntimeException("constraint violation");
            }
            if (s.getId() == null) {
                s.setId(ids.getAndIncrement());
                subscriberStore.add(s);
            }
            return s;
        }).when(subscribers).save(any());

        MigrationImportService.Promoted result = service.promote(batch, "grace");

        // This asserts the counting and the reporting. It does NOT prove the
        // rows are isolated from each other: that is a transaction property and
        // a mocked repository has no transaction to abort. The first version of
        // this code passed a test like this one and still rolled the whole batch
        // back against PostgreSQL, so the isolation is proven against a real
        // database instead.
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.problems()).anyMatch(p -> p.contains("Explodes"));
    }

    @Test
    @DisplayName("a login taken between staging and promoting is caught at promotion")
    void lateCollisionIsCaught() {
        Long batch = service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "Mary", "pppoe", "mary", "phone", "0712000013", "price", "2500",
                        "expires", "2099-12-31")),
                "grace").batchId();
        // Somebody adds that login by hand while the operator reads the report.
        subscriberStore.add(Subscriber.builder().id(99L).fullName("Typed In").pppoeUsername("mary").build());

        MigrationImportService.Promoted result = service.promote(batch, "grace");

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("a batch cannot be promoted twice")
    void promoteIsOnceOnly() {
        Long batch = service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "Mary", "pppoe", "mary", "phone", "0712000014", "price", "2500")),
                "grace").batchId();
        service.promote(batch, "grace");

        assertThatThrownBy(() -> service.promote(batch, "grace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been brought across");
    }

    @Test
    @DisplayName("discarding after promotion is refused, because it would not undo anything")
    void cannotDiscardAfterPromoting() {
        Long batch = service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "Mary", "pppoe", "mary")), "grace").batchId();
        service.promote(batch, "grace");

        assertThatThrownBy(() -> service.discard(batch, "grace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exist");
    }

    @Test
    @DisplayName("an empty file and an implausibly large one are both refused")
    void boundsOnAnUpload() {
        assertThatThrownBy(() -> service.stage(MigrationSource.GENERIC, null, null, List.of(), "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no rows");

        List<Map<String, String>> huge = new ArrayList<>();
        for (int i = 0; i < 20_001; i++) {
            huge.add(row("name", "Person " + i, "pppoe", "p" + i));
        }
        assertThatThrownBy(() -> service.stage(MigrationSource.GENERIC, null, null, huge, "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Split the export");
    }

    @Test
    @DisplayName("staging creates nobody, whatever is in the file")
    void stagingIsSafe() {
        service.stage(MigrationSource.SPLYNX, null, null, List.of(
                row("customer_name", "Mary", "login", "mary", "tariff", "Home 10Mbps"),
                row("customer_name", "John", "login", "john", "tariff", "Home 20Mbps")), "grace");

        // The property that makes it safe to run against a live book on a Tuesday.
        assertThat(subscriberStore).isEmpty();
    }

    // --- what the old system would not give us ---

    @Test
    @DisplayName("a login with no password still comes across, with a new one, and says so")
    void missingPasswordIsGeneratedAndFlagged() {
        // Splynx and most others will not export PPPoE secrets in the clear.
        // Refusing those rows would make the importer useless for the system it
        // most needs to read.
        Long batch = service.stage(MigrationSource.SPLYNX, null, null, List.of(row(
                "customer_name", "No Password", "login", "nopass", "phone", "0712000020",
                "tariff", "Home 10Mbps", "end_date", "2099-12-31")), "grace").batchId();
        assertThat(only().getVerdictNote()).contains("no password", "new one will be made");

        MigrationImportService.Promoted result = service.promote(batch, "grace");

        assertThat(result.created()).isEqualTo(1);
        Subscriber made = subscriberStore.get(0);
        assertThat(made.getPppoePassword()).isNotBlank();
        // The operator has to be told, or the customer is offline the day they
        // are moved onto a Zidi router and nobody knows why.
        assertThat(result.problems()).anyMatch(p -> p.contains("new PPPoE password"));
    }

    @Test
    @DisplayName("no paid-up date means suspended on arrival, not a free month")
    void missingPaidUntilArrivesSuspended() {
        Long batch = service.stage(MigrationSource.GENERIC, null, null, List.of(row(
                "name", "Unknown Expiry", "pppoe", "unknown", "phone", "0712000021",
                "price", "2500")), "grace").batchId();

        service.promote(batch, "grace");

        Subscriber made = subscriberStore.get(0);
        assertThat(made.getPaidUntil()).isNotNull();
        assertThat(made.getStatus()).isEqualTo(Subscriber.Status.SUSPENDED);
    }

    @Test
    @DisplayName("a row with no phone number cannot be created, and is told why")
    void missingPhoneIsIncomplete() {
        // Not taste: phone_number is NOT NULL on subscribers. Found by promoting
        // against a real database, where every one of these had inserted happily
        // against mocks.
        service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "No Phone", "pppoe", "nophone", "price", "2500")), "grace");

        assertThat(only().getVerdict()).isEqualTo(MigrationRow.Verdict.INCOMPLETE);
        assertThat(only().getVerdictNote()).contains("No phone number");
    }

    @Test
    @DisplayName("a row with nothing to bill cannot be created, and is told why")
    void missingPriceIsIncomplete() {
        service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "No Price", "pppoe", "noprice", "phone", "0712000022")),
                "grace");

        assertThat(only().getVerdict()).isEqualTo(MigrationRow.Verdict.INCOMPLETE);
        assertThat(only().getVerdictNote()).contains("nothing to bill");
    }

    @Test
    @DisplayName("a re-upload says 'already here' rather than complaining about a missing price")
    void collisionBeatsIncompleteness() {
        subscriberStore.add(Subscriber.builder().id(99L).fullName("Already Here")
                .pppoeUsername("mary").build());

        service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", "Mary", "pppoe", "mary", "phone", "0712000023")), "grace");

        // Identity before completeness: on a re-upload the operator wants to hear
        // that the customer is already across, not a complaint about the file.
        assertThat(only().getVerdict()).isEqualTo(MigrationRow.Verdict.COLLISION);
    }

    @Test
    @DisplayName("a generated password is not something anybody could guess")
    void generatedPasswordsAreNotGuessable() {
        String a = MigrationRowPromoter.newPassword();
        String b = MigrationRowPromoter.newPassword();

        assertThat(a).hasSize(12).isNotEqualTo(b);
        // No l or o, and no 0 or 1, so it can be read out down a phone line to
        // somebody standing on a ladder without being misheard.
        assertThat(a).doesNotContainAnyWhitespaces().matches("[a-kmnp-z2-9]{12}");
    }

    @Test
    @DisplayName("the same login twice in one file is caught while the operator is still deciding")
    void duplicateLoginWithinTheFile() {
        MigrationImportService.Staged staged = service.stage(MigrationSource.GENERIC, null, null,
                List.of(
                        row("name", "Dup A", "pppoe", "dup", "phone", "0712000030", "price", "2500"),
                        row("name", "Dup B", "pppoe", "dup", "phone", "0712000031", "price", "2500")),
                "grace");

        // Both rows pass a check that only looks at the existing book, and the
        // second then fails at promotion -- which works, but says nothing useful
        // while the operator is still reading the report.
        assertThat(staged.ready()).isEqualTo(1);
        assertThat(staged.collisions()).isEqualTo(1);
        assertThat(rowStore.get(1).getVerdictNote()).contains("more than once in this file");
    }

    @Test
    @DisplayName("an over-long value is cut down rather than failing the whole upload")
    void oversizeValuesAreCapped() {
        String tooLong = "X".repeat(400);

        MigrationImportService.Staged staged = service.stage(MigrationSource.GENERIC, null, null,
                List.of(row("name", tooLong, "pppoe", "long", "phone", "0712000032",
                        "price", "2500")), "grace");

        // One three-hundred-character company name used to fail a three thousand
        // row upload with an opaque 500 and no clue which row did it.
        assertThat(staged.total()).isEqualTo(1);
        assertThat(only().getFullName()).hasSize(255);
    }

    @Test
    @DisplayName("capping never lengthens or nulls a value that already fits")
    void cappingLeavesNormalValuesAlone() {
        assertThat(MigrationImportService.cap("Mary Kamau", 255)).isEqualTo("Mary Kamau");
        assertThat(MigrationImportService.cap("  padded  ", 255)).isEqualTo("padded");
        assertThat(MigrationImportService.cap(null, 255)).isNull();
    }
}
