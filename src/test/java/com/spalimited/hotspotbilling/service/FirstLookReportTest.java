package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.RevenueFinding;
import com.spalimited.hotspotbilling.repository.RevenueFindingRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The answer to "what is this costing me".
 *
 * <p>Competitors bill; none of them tell an ISP what is leaking. The checks
 * already existed and ran nightly into a list of nine finding kinds, which is a
 * diagnostic rather than an answer. These tests are about the difference: the
 * money is added up, split into what can still be collected and what has already
 * gone, ordered so the two lines an operator actually reads are the two that
 * matter, and honest about what the sweep could not see.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirstLookReportTest {

    @Mock private RevenueAuditService audit;
    @Mock private RevenueFindingRepository findings;
    @Mock private MoneyService money;

    @InjectMocks
    private FirstLookReport report;

    private final List<RevenueFinding> open = new ArrayList<>();
    private final Map<String, Object> swept = new HashMap<>();

    @BeforeEach
    void setUp() {
        open.clear();
        swept.clear();
        swept.put("routersChecked", true);
        swept.put("skippedRouters", 0);

        when(audit.sweep(anyString())).thenReturn(swept);
        when(findings.findByStatus(any())).thenReturn(open);
        // A currency that is deliberately not shillings, so anything that
        // hard-codes KES shows up here rather than in front of an operator.
        when(money.format(any())).thenAnswer(i -> {
            BigDecimal v = i.getArgument(0);
            return "NGN " + (v == null ? "0" : v.stripTrailingZeros().toPlainString());
        });
    }

    private void finding(RevenueFinding.Kind kind, String amount, RevenueFinding.Severity sev) {
        open.add(RevenueFinding.builder()
                .id((long) (open.size() + 1))
                .kind(kind)
                .severity(sev)
                .subject("subject")
                .detail("detail")
                .amount(new BigDecimal(amount))
                .status(RevenueFinding.Status.OPEN)
                .build());
    }

    @SuppressWarnings("unchecked")
    private List<FirstLookReport.Line> linesOf(Map<String, Object> out) {
        return (List<FirstLookReport.Line>) out.get("lines");
    }

    @Test
    @DisplayName("a clean book says so plainly, rather than showing an empty table")
    void nothingWrong() {
        Map<String, Object> out = report.run("grace");

        assertThat(out.get("findings")).isEqualTo(0);
        assertThat((String) out.get("headline")).contains("Nothing is leaking");
        assertThat(linesOf(out)).isEmpty();
    }

    @Test
    @DisplayName("the headline is a figure, because that is the question being asked")
    void headlineCarriesTheMoney() {
        finding(RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, "12000", RevenueFinding.Severity.HIGH);
        finding(RevenueFinding.Kind.UNDERPAID, "3000", RevenueFinding.Severity.MEDIUM);

        Map<String, Object> out = report.run("grace");

        assertThat((String) out.get("headline")).contains("NGN 15000", "2 finding");
    }

    @Test
    @DisplayName("findings of one kind are added up into a single line")
    void findingsAreGroupedAndTotalled() {
        finding(RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, "5000", RevenueFinding.Severity.HIGH);
        finding(RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, "7000", RevenueFinding.Severity.MEDIUM);

        List<FirstLookReport.Line> lines = linesOf(report.run("grace"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).count()).isEqualTo(2);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("12000");
        // The worst severity in the group, not the last one seen.
        assertThat(lines.get(0).severity()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("the biggest money is first")
    void biggestFirst() {
        finding(RevenueFinding.Kind.UNDERPAID, "500", RevenueFinding.Severity.LOW);
        finding(RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, "40000", RevenueFinding.Severity.HIGH);
        finding(RevenueFinding.Kind.UNAPPLIED_PAYMENT, "9000", RevenueFinding.Severity.MEDIUM);

        List<FirstLookReport.Line> lines = linesOf(report.run("grace"));

        // Nobody reads past the second row.
        assertThat(lines).extracting(FirstLookReport.Line::kind)
                .containsExactly("LAPSED_NOT_SUSPENDED", "UNAPPLIED_PAYMENT", "UNDERPAID");
    }

    @Test
    @DisplayName("money that can still be collected is kept apart from money that has gone")
    void recoverableIsSeparate() {
        // Somebody who paid and got nothing is owed a service; that is not money
        // waiting to be collected.
        finding(RevenueFinding.Kind.PAID_NO_SERVICE, "2000", RevenueFinding.Severity.HIGH);
        finding(RevenueFinding.Kind.LAPSED_NOT_SUSPENDED, "8000", RevenueFinding.Severity.HIGH);

        Map<String, Object> out = report.run("grace");

        // Lumping them together overstates the case, which is the fastest way to
        // lose an operator on the first screen they see.
        assertThat((BigDecimal) out.get("recoverable")).isEqualByComparingTo("8000");
        assertThat((BigDecimal) out.get("alreadyGone")).isEqualByComparingTo("2000");
        assertThat((BigDecimal) out.get("total")).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("every line says what it means and what to do about it")
    void linesExplainThemselves() {
        finding(RevenueFinding.Kind.GHOST_PPPOE_SECRET, "0", RevenueFinding.Severity.HIGH);

        FirstLookReport.Line line = linesOf(report.run("grace")).get(0);

        assertThat(line.label()).isEqualTo("Logins on the router with no customer");
        assertThat(line.meaning()).isNotBlank();
        assertThat(line.action()).isNotBlank();
    }

    @Test
    @DisplayName("the money is shown in the operator's own currency")
    void currencyFollowsTheOperator() {
        finding(RevenueFinding.Kind.UNDERPAID, "1500", RevenueFinding.Severity.LOW);

        Map<String, Object> out = report.run("grace");

        // The audit used to print KES at a Lagos operator on the one screen that
        // is entirely about their money.
        assertThat((String) out.get("totalText")).startsWith("NGN");
        assertThat(linesOf(out).get(0).amountText()).startsWith("NGN");
    }

    @Test
    @DisplayName("a sweep that could not read the routers says so instead of looking clean")
    void unreadRoutersAreDeclared() {
        swept.put("routersChecked", false);

        Map<String, Object> out = report.run("grace");

        // A clean report from a sweep that saw half the system is the worst thing
        // this screen could produce.
        assertThat((String) out.get("coverage")).contains("could not be read", "books only");
    }

    @Test
    @DisplayName("some routers missed is reported as partial, not as complete")
    void partialCoverageIsDeclared() {
        swept.put("skippedRouters", 2);

        Map<String, Object> out = report.run("grace");

        assertThat((String) out.get("coverage")).contains("2 router", "incomplete");
    }

    @Test
    @DisplayName("with everything reachable, coverage says so")
    void fullCoverage() {
        assertThat((String) report.run("grace").get("coverage"))
                .isEqualTo("Books and routers both checked.");
    }

    @Test
    @DisplayName("findings with no figure yet are counted but do not invent an amount")
    void findingsWithoutAmounts() {
        open.add(RevenueFinding.builder().id(1L).kind(RevenueFinding.Kind.GHOST_HOTSPOT_USER)
                .severity(RevenueFinding.Severity.HIGH).subject("s").detail("d")
                .status(RevenueFinding.Status.OPEN).build());

        Map<String, Object> out = report.run("grace");

        assertThat(out.get("findings")).isEqualTo(1);
        assertThat((BigDecimal) out.get("total")).isEqualByComparingTo("0");
        assertThat((String) out.get("headline")).contains("None of them have a figure");
    }
}
