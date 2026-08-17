package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.AgentPayoutSettings;
import com.spalimited.hotspotbilling.domain.AiSettings;
import com.spalimited.hotspotbilling.domain.CapacitySettings;
import com.spalimited.hotspotbilling.domain.CreditSettings;
import com.spalimited.hotspotbilling.domain.FieldSettings;
import com.spalimited.hotspotbilling.domain.HealthAlert;
import com.spalimited.hotspotbilling.domain.Incident;
import com.spalimited.hotspotbilling.domain.LoyaltySettings;
import com.spalimited.hotspotbilling.domain.MessagingSettings;
import com.spalimited.hotspotbilling.domain.OffpeakSettings;
import com.spalimited.hotspotbilling.domain.OperatorAlertSettings;
import com.spalimited.hotspotbilling.domain.OpsSettings;
import com.spalimited.hotspotbilling.domain.PaybillSettings;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.ReferralSettings;
import com.spalimited.hotspotbilling.domain.RevenueAuditSettings;
import com.spalimited.hotspotbilling.domain.RevenueFinding;
import com.spalimited.hotspotbilling.repository.CommissionPayoutRepository;
import com.spalimited.hotspotbilling.repository.HealthAlertRepository;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.OutboundMessageRepository;
import com.spalimited.hotspotbilling.repository.RevenueFindingRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What the assistant is told, and — far more important — what it is not.
 *
 * <p>This text is sent to a third party under the operator's own API key. The
 * leak test here is the one that must never be deleted: it sets a real-shaped
 * secret on every settings object that has one and asserts none of them
 * survives into the prompt.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemContextServiceTest {

    private static final String GROQ_KEY = "gsk_8Mlidc6NORNdvsqAiKWiWGdyb3FY7Dyw314forHwdHmd";
    private static final String SMS_KEY = "atsk_9f3c1b77e2d64a0fb8c5e1a2d7409cbe3f81a6d2";
    private static final String WA_TOKEN = "EAAG9ZBx1ZC2QBO7ZBk4ZDZDaaZAZBwvZC8kQZDZD0LmNqPqR3sT";
    private static final String WATCHDOG = "https://hc-ping.com/7f1c9b3e-2a44-4c81-9d0e-5b6a7c8d9e01";

    @Mock private HealthMonitorService health;
    @Mock private BackupWatchService backups;
    @Mock private HealthAlertRepository alerts;
    @Mock private IncidentRepository incidents;
    @Mock private RevenueFindingRepository findings;
    @Mock private SupportTicketRepository tickets;
    @Mock private CommissionPayoutRepository payouts;
    @Mock private OutboundMessageRepository outbound;
    @Mock private MessagingSettingsService messagingSettings;
    @Mock private EmailService emailService;
    @Mock private PaymentGatewayService gateways;
    @Mock private MpesaService mpesa;
    @Mock private OperatorAlertSettingsService alertSettings;
    @Mock private FieldOpsService fieldOps;
    @Mock private AgentPayoutService agentPayouts;
    @Mock private OffPeakService offPeak;
    @Mock private CapacityService capacity;
    @Mock private CreditService credit;
    @Mock private PaybillActivationService paybill;
    @Mock private RevenueAuditService revenueAudit;
    @Mock private AiSettingsService aiSettings;
    @Mock private LoyaltyService loyalty;
    @Mock private ReferralService referrals;
    @Mock private PortalSettingsService portalSettings;

    private SystemContextService service;
    private MessagingSettings messaging;
    private OpsSettings ops;
    private CreditSettings creditSettings;
    private OffpeakSettings offpeakSettings;

    @BeforeEach
    void setUp() {
        service = new SystemContextService(health, backups, alerts, incidents, findings, tickets,
                payouts, outbound, messagingSettings, emailService, gateways, mpesa, alertSettings,
                fieldOps, agentPayouts, offPeak, capacity, credit, paybill, revenueAudit, aiSettings,
                loyalty, referrals, portalSettings);

        // Every secret this system holds, set to something real-shaped.
        messaging = MessagingSettings.builder()
                .id(1L).smsEnabled(true).smsProvider("AFRICASTALKING")
                .smsUsername("spawifi").smsApiKey(SMS_KEY).smsSenderId("SPAWIFI")
                .whatsappEnabled(true).whatsappPhoneNumberId("123456789")
                .whatsappAccessToken(WA_TOKEN).alertPhone("254700999888").build();
        ops = OpsSettings.builder().id(1L).heartbeatUrl(WATCHDOG).build();
        creditSettings = CreditSettings.builder().id(1L).build();
        offpeakSettings = OffpeakSettings.builder().id(1L).build();

        when(messagingSettings.settings()).thenReturn(messaging);
        when(backups.settings()).thenReturn(ops);
        when(credit.settings()).thenReturn(creditSettings);
        when(offPeak.settings()).thenReturn(offpeakSettings);
        when(aiSettings.get()).thenReturn(AiSettings.builder()
                .id(1L).enabled(true).apiKey(GROQ_KEY).draftTicketReplies(true).build());
        when(alertSettings.get()).thenReturn(OperatorAlertSettings.builder().id(1L).build());
        when(fieldOps.settings()).thenReturn(FieldSettings.builder().id(1L).build());
        when(agentPayouts.settings()).thenReturn(AgentPayoutSettings.builder().id(1L).build());
        when(capacity.settings()).thenReturn(CapacitySettings.builder().id(1L).build());
        when(paybill.settings()).thenReturn(PaybillSettings.builder().id(1L).build());
        when(revenueAudit.settings()).thenReturn(RevenueAuditSettings.builder().id(1L).build());
        when(loyalty.settings()).thenReturn(LoyaltySettings.builder().id(1L).build());
        when(referrals.settings()).thenReturn(ReferralSettings.builder().id(1L).build());
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().businessName("SPA WiFi").build());

        when(emailService.isEnabled()).thenReturn(false);
        when(gateways.stkAvailable()).thenReturn(true);
        when(gateways.transactionStatusAvailable()).thenReturn(false);
        when(mpesa.canSendMoney()).thenReturn(false);

        // A quiet, healthy system unless a test says otherwise.
        when(alerts.findByStatus(HealthAlert.Status.OPEN)).thenReturn(List.of());
        when(health.overview()).thenReturn(Map.of("jobs", List.of(
                Map.of("label", "router monitoring", "status", "ok"))));
        when(backups.overview()).thenReturn(Map.of(
                "healthy", true, "lastVerified", true, "lastOffsite", true));
        when(incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN)).thenReturn(List.of());
        when(findings.findByStatus(RevenueFinding.Status.OPEN)).thenReturn(List.of());
        when(tickets.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(payouts.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(outbound.findByCreatedAtAfter(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("No credential of any kind reaches the model")
    void neverLeaksASecret() {
        String context = service.forAssistant();

        assertThat(context)
                .doesNotContain(GROQ_KEY)
                .doesNotContain(SMS_KEY)
                .doesNotContain(WA_TOKEN)
                .doesNotContain(WATCHDOG)
                .doesNotContain("spawifi");
        // What it may say is only whether a thing is configured at all.
        assertThat(context).contains("credentials set");
    }

    @Test
    @DisplayName("The redaction pass catches a key somebody adds later without thinking")
    void redactsKeyShapedText() {
        String leaked = SystemContextService.redact(
                "the key is " + GROQ_KEY + " and the token is " + WA_TOKEN);

        assertThat(leaked).doesNotContain(GROQ_KEY).doesNotContain(WA_TOKEN);
        assertThat(leaked).contains("[redacted]");
        // Ordinary prose must survive it intact.
        assertThat(SystemContextService.redact("Off-peak night rate: ON, 30% off"))
                .isEqualTo("Off-peak night rate: ON, 30% off");
    }

    @Test
    @DisplayName("It can say what the system does, not just what its numbers are")
    void explainsHowTheSystemWorks() {
        String context = service.forAssistant();

        assertThat(context).contains("HOW THIS SYSTEM WORKS");
        assertThat(context).contains("Dunning", "Win-back", "Revenue audit");
        assertThat(context).contains("WHAT IT WILL NEVER DO ON ITS OWN");
    }

    @Test
    @DisplayName("Switched-off automations are reported as off, not described as if running")
    void reportsWhatIsOff() {
        String context = service.forAssistant();

        assertThat(context).contains("Pay later (Lipa Baadaye): off");
        assertThat(context).contains("Off-peak night rate: off");
        assertThat(context).contains("Agent commission payouts: off");
        assertThat(context).contains("M-Pesa payments out (B2C): no");
    }

    @Test
    @DisplayName("Switching something on changes what it is told, without a restart")
    void reflectsLiveConfiguration() {
        creditSettings.setEnabled(true);
        creditSettings.setMaxAdvance(new BigDecimal("150"));
        offpeakSettings.setEnabled(true);
        offpeakSettings.setDiscountPercent(35);

        String context = service.forAssistant();

        assertThat(context).contains("Pay later (Lipa Baadaye): ON", "up to KES 150");
        assertThat(context).contains("Off-peak night rate: ON, 35% off");
    }

    @Test
    @DisplayName("A clean system says so rather than leaving the model to guess")
    void statesTheAbsenceOfProblems() {
        String context = service.forAssistant();

        assertThat(context).contains("Health: no open alerts");
        assertThat(context).contains("Scheduled jobs: all running normally");
        assertThat(context).contains("Revenue audit: nothing outstanding");
        assertThat(context).contains("Outages: none open");
    }

    @Test
    @DisplayName("Real problems are named, with the detail needed to act")
    void surfacesAnomalies() {
        when(alerts.findByStatus(HealthAlert.Status.OPEN)).thenReturn(List.of(
                HealthAlert.builder().id(1L).checkKey("mpesa.silence")
                        .severity(HealthAlert.Severity.CRITICAL)
                        .title("No M-Pesa payment has arrived in 9 hours")
                        .detail("Check that Safaricom can still reach this server.")
                        .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build()));
        when(health.overview()).thenReturn(Map.of("jobs", List.of(
                Map.of("label", "router monitoring", "status", "stale"))));
        when(backups.overview()).thenReturn(Map.of(
                "healthy", false, "lastVerified", false, "lastOffsite", false));
        when(incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN)).thenReturn(List.of(
                Incident.builder().id(1L).status(Incident.Status.OPEN).title("Kasarani down")
                        .startedAt(Instant.now().minus(Duration.ofMinutes(40))).build()));
        when(findings.findByStatus(RevenueFinding.Status.OPEN)).thenReturn(List.of(
                RevenueFinding.builder().id(1L).kind(RevenueFinding.Kind.PAID_NO_SERVICE)
                        .subject("254700111222").amount(new BigDecimal("300"))
                        .status(RevenueFinding.Status.OPEN).build()));

        String context = service.forAssistant();

        assertThat(context).contains("CRITICAL: No M-Pesa payment has arrived in 9 hours");
        assertThat(context).contains("router monitoring (stale)");
        assertThat(context).contains("NO recent good backup");
        assertThat(context).contains("Kasarani down");
        assertThat(context).contains("PAID_NO_SERVICE x1", "KES 300");
    }
}
