package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.CreditAdvance;
import com.spalimited.hotspotbilling.domain.HealthAlert;
import com.spalimited.hotspotbilling.domain.Incident;
import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.RevenueFinding;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.repository.CreditAdvanceRepository;
import com.spalimited.hotspotbilling.repository.HealthAlertRepository;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.RevenueFindingRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SubscriptionPaymentRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The owner's daily briefing. What is worth testing here is not the arithmetic
 * but the editing: a briefing that pads itself out with "0 lapsed, 0 expiring,
 * 0 tickets" every morning stops being read, and a comparison that divides by
 * a quiet week would report an infinite rise.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyBriefServiceTest {

    @Mock private PaymentRepository payments;
    @Mock private SubscriptionPaymentRepository subscriptionPayments;
    @Mock private SubscriberRepository subscribers;
    @Mock private SupportTicketRepository tickets;
    @Mock private RevenueFindingRepository findings;
    @Mock private HealthAlertRepository healthAlerts;
    @Mock private IncidentRepository incidents;
    @Mock private RouterRepository routers;
    @Mock private CreditAdvanceRepository creditAdvances;
    @Mock private OperatorAlertSettingsService alertSettings;
    @Mock private MessagingSettingsService messagingSettings;
    @Mock private EmailSettingsService emailSettings;
    @Mock private SmsService smsService;
    @Mock private EmailService emailService;
    @Mock private PortalSettingsService portalSettings;

    private DailyBriefService service;

    @BeforeEach
    void setUp() {
        service = new DailyBriefService(payments, subscriptionPayments, subscribers, tickets,
                findings, healthAlerts, incidents, routers, creditAdvances, alertSettings,
                messagingSettings, emailSettings, smsService, emailService, portalSettings);

        when(portalSettings.settings()).thenReturn(PortalSettings.builder().businessName("SPA WiFi").build());
        // A quiet, healthy day unless a test says otherwise.
        when(payments.sumAmountByStatusSince(any(), any())).thenReturn(BigDecimal.ZERO);
        when(payments.sumAmountByStatusBetween(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(subscriptionPayments.sumAmountByStatusSince(any(), any())).thenReturn(BigDecimal.ZERO);
        when(subscriptionPayments.sumAmountByStatusBetween(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(subscribers.findAll()).thenReturn(List.of());
        when(tickets.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(findings.findByStatus(RevenueFinding.Status.OPEN)).thenReturn(List.of());
        when(healthAlerts.findByStatus(HealthAlert.Status.OPEN)).thenReturn(List.of());
        when(incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN)).thenReturn(List.of());
        when(creditAdvances.findByStatusOrderByDueAtAsc(CreditAdvance.Status.OUTSTANDING)).thenReturn(List.of());
        when(routers.findByEnabledTrue()).thenReturn(List.of(
                Router.builder().id(1L).name("Kilimani").enabled(true).online(true).build()));
    }

    private void tookToday(String hotspot, String lastWeek) {
        when(payments.sumAmountByStatusSince(any(), any())).thenReturn(new BigDecimal(hotspot));
        when(payments.countByStatusAndCompletedAtAfter(any(), any())).thenReturn(12L);
        when(payments.sumAmountByStatusBetween(any(), any(), any())).thenReturn(new BigDecimal(lastWeek));
    }

    @Test
    @DisplayName("A quiet day says so in a few lines, not a page of zeroes")
    void staysShortOnAQuietDay() {
        String brief = service.build().shortForm();

        assertThat(brief).contains("SPA WiFi", "💰", "All 1 router(s) online");
        assertThat(brief).doesNotContain("lapsed", "open ticket", "audit finding");
        assertThat(brief.lines().count()).isLessThan(8);
    }

    @Test
    @DisplayName("Takings are read against the same weekday last week")
    void comparesWithLastWeek() {
        tookToday("12000", "8000");
        assertThat(service.build().shortForm()).contains("KES 12000", "↑ 50%");

        tookToday("4000", "8000");
        assertThat(service.build().shortForm()).contains("↓ 50%");
    }

    @Test
    @DisplayName("A swing too small to mean anything is not dressed up as a trend")
    void ignoresNoise() {
        tookToday("8300", "8000");
        assertThat(service.build().shortForm()).contains("about the same as last week");
    }

    @Test
    @DisplayName("A week with no takings produces no percentage rather than a divide by zero")
    void survivesAnEmptyBaseline() {
        tookToday("5000", "0");

        String brief = service.build().shortForm();

        assertThat(brief).contains("KES 5000");
        assertThat(brief).doesNotContain("%");
    }

    @Test
    @DisplayName("Today is compared against the same slice of last week, not the whole day")
    void comparesLikeForLike() {
        service.build();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(payments).sumAmountByStatusBetween(any(), from.capture(), to.capture());

        Instant startOfToday = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        assertThat(from.getValue()).isEqualTo(startOfToday.minus(Duration.ofDays(7)));
        // The window ends at "this time last week" — comparing a part-day
        // against a whole one would report a collapse every morning.
        assertThat(Duration.between(to.getValue(), Instant.now().minus(Duration.ofDays(7))).abs())
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Lapses and imminent expiries are named while there is time to act")
    void namesWhoIsAboutToGo() {
        Instant now = Instant.now();
        when(subscribers.findAll()).thenReturn(List.of(
                Subscriber.builder().id(1L).fullName("Jane Doe").phoneNumber("254733111222")
                        .status(Subscriber.Status.SUSPENDED)
                        .paidUntil(now.minus(Duration.ofMinutes(30)))
                        .createdAt(now.minus(Duration.ofDays(90))).build(),
                Subscriber.builder().id(2L).fullName("Peter Mwangi").phoneNumber("254733111333")
                        .status(Subscriber.Status.ACTIVE)
                        .paidUntil(now.plus(Duration.ofDays(2)))
                        .createdAt(now.minus(Duration.ofDays(200))).build()));

        String brief = service.build().shortForm();
        String full = service.build().longForm();

        assertThat(brief).contains("1 lapsed", "1 expiring in 3d");
        assertThat(full).contains("Jane Doe", "Peter Mwangi");
    }

    @Test
    @DisplayName("Unclaimed jobs and audit findings are called out by name")
    void surfacesWorkAndRisk() {
        SupportTicket unclaimed = SupportTicket.builder().id(42L).customerName("Jane Doe")
                .phoneNumber("254733111222").subject("No internet")
                .priority(SupportTicket.Priority.HIGH).status(SupportTicket.Status.OPEN)
                .createdAt(Instant.now().minus(Duration.ofHours(2)))
                .updatedAt(Instant.now()).build();
        when(tickets.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(unclaimed));
        when(findings.findByStatus(RevenueFinding.Status.OPEN)).thenReturn(List.of(
                RevenueFinding.builder().id(1L).kind(RevenueFinding.Kind.PAID_NO_SERVICE)
                        .subject("254700111222").amount(new BigDecimal("300"))
                        .status(RevenueFinding.Status.OPEN).build()));

        String brief = service.build().shortForm();

        assertThat(brief).contains("1 open ticket(s)", "1 nobody has taken");
        assertThat(brief).contains("1 audit finding(s)", "KES 300");
    }

    @Test
    @DisplayName("It does not send twice in a day, however often the hour comes round")
    void sendsOncePerDay() {
        OperatorAlertSettings s = OperatorAlertSettings.builder()
                .id(1L)
                .salesDigestEnabled(true)
                .salesDigestHour(java.time.LocalTime.now(ZoneId.systemDefault()).getHour())
                .lastDigestSent(LocalDate.now(ZoneId.systemDefault()))
                .build();
        when(alertSettings.get()).thenReturn(s);
        when(messagingSettings.alertPhone()).thenReturn("254700999888");

        service.maybeSend();

        verify(smsService, never()).trySend(anyString(), anyString());
        verify(alertSettings, never()).markDigestSent(any());
    }

    @Test
    @DisplayName("Switched off, nothing is built or sent")
    void respectsTheOffSwitch() {
        when(alertSettings.get()).thenReturn(OperatorAlertSettings.builder()
                .id(1L).salesDigestEnabled(false).build());

        service.maybeSend();

        verify(smsService, never()).trySend(anyString(), anyString());
        verify(subscribers, never()).findAll();
    }
}
