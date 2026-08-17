package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Agent;
import com.spalimited.hotspotbilling.domain.AgentPayoutSettings;
import com.spalimited.hotspotbilling.domain.CommissionPayout;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.repository.AgentPayoutSettingsRepository;
import com.spalimited.hotspotbilling.repository.AgentRepository;
import com.spalimited.hotspotbilling.repository.CommissionPayoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paying agents. Every test here is about one of two ways to lose money: a
 * payout treated as settled before Safaricom said so, and the same balance
 * paid twice because the first attempt was still in flight.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentPayoutServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock private AgentPayoutSettingsRepository settingsRepo;
    @Mock private CommissionPayoutRepository payouts;
    @Mock private AgentRepository agents;
    @Mock private AgentService agentService;
    @Mock private MpesaService mpesa;
    @Mock private SmsService smsService;
    @Mock private MessagingSettingsService messagingSettings;
    @Mock private PortalSettingsService portalSettings;

    private AgentPayoutService service;

    private final Map<Long, CommissionPayout> stored = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private Agent grace;
    private AgentPayoutSettings settings;

    @BeforeEach
    void setUp() {
        service = new AgentPayoutService(settingsRepo, payouts, agents, agentService, mpesa,
                smsService, messagingSettings, portalSettings);

        settings = AgentPayoutSettings.builder().id(1L).enabled(true).build();
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(settings));
        when(settingsRepo.save(any(AgentPayoutSettings.class))).thenAnswer(i -> i.getArgument(0));

        grace = Agent.builder().id(3L).fullName("Grace Achieng").code("GRC")
                .phoneNumber("254712345678").commissionPercent(20).active(true)
                .commissionPaid(BigDecimal.ZERO).build();
        when(agents.findAllByOrderByFullNameAsc()).thenReturn(List.of(grace));
        when(agents.findAll()).thenReturn(List.of(grace));
        when(agents.findById(3L)).thenReturn(Optional.of(grace));
        when(agents.save(any(Agent.class))).thenAnswer(i -> i.getArgument(0));

        owes("2000");
        when(mpesa.canSendMoney()).thenReturn(true);
        when(mpesa.b2cPayment(anyString(), any(), any(), anyString())).thenReturn("AG_CONV_1");
        when(messagingSettings.alertPhone()).thenReturn("254700999888");
        when(portalSettings.settings()).thenReturn(PortalSettings.builder().businessName("SPA WiFi").build());

        when(payouts.save(any(CommissionPayout.class))).thenAnswer(i -> {
            CommissionPayout p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(ids.getAndIncrement());
                p.setCreatedAt(Instant.now());
            }
            stored.put(p.getId(), p);
            return p;
        });
        when(payouts.findById(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(payouts.findTop200ByOrderByCreatedAtDesc()).thenAnswer(i -> List.copyOf(stored.values()));
        when(payouts.findByStatusInOrderByCreatedAtAsc(any())).thenAnswer(i -> {
            List<CommissionPayout.Status> want = new ArrayList<>(i.getArgument(0));
            return stored.values().stream().filter(p -> want.contains(p.getStatus())).toList();
        });
        when(payouts.findByConversationId(anyString())).thenAnswer(i -> stored.values().stream()
                .filter(p -> i.getArgument(0).equals(p.getConversationId())).findFirst());
    }

    private void owes(String amount) {
        when(agentService.salesFor(grace)).thenReturn(Map.of("commissionOwed", new BigDecimal(amount)));
    }

    private void resultArrives(String conversationId, int code, String receipt) {
        String body = """
                {"Result":{"ConversationID":"%s","ResultCode":%d,"ResultDesc":"%s",
                 "ResultParameters":{"ResultParameter":[{"Key":"TransactionReceipt","Value":"%s"}]}}}
                """.formatted(conversationId, code, code == 0 ? "The service request is processed successfully."
                        : "The initiator information is invalid.", receipt);
        service.handleB2cResult(JSON.readTree(body));
    }

    @Test
    @DisplayName("A queued payout does not count as paid until Safaricom confirms it")
    void doesNotCreditUntilConfirmed() {
        settings.setAutoSend(true);
        service.runNow("test");

        CommissionPayout sent = stored.values().iterator().next();
        assertThat(sent.getStatus()).isEqualTo(CommissionPayout.Status.SENT);
        assertThat(grace.getCommissionPaid()).isEqualByComparingTo("0");

        resultArrives("AG_CONV_1", 0, "QK12ABC34D");

        assertThat(stored.get(sent.getId()).getStatus()).isEqualTo(CommissionPayout.Status.PAID);
        assertThat(stored.get(sent.getId()).getReceipt()).isEqualTo("QK12ABC34D");
        assertThat(grace.getCommissionPaid()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("A failed payout leaves the agent still owed, and tells the operator")
    void aFailureLeavesTheDebtStanding() {
        settings.setAutoSend(true);
        service.runNow("test");

        resultArrives("AG_CONV_1", 2001, "");

        CommissionPayout p = stored.values().iterator().next();
        assertThat(p.getStatus()).isEqualTo(CommissionPayout.Status.FAILED);
        assertThat(p.getError()).contains("initiator information is invalid");
        assertThat(grace.getCommissionPaid()).isEqualByComparingTo("0");
        verify(smsService).trySend(org.mockito.ArgumentMatchers.eq("254700999888"),
                org.mockito.ArgumentMatchers.contains("failed"));
    }

    @Test
    @DisplayName("Safaricom repeating a result does not pay the agent twice")
    void ignoresARepeatedResult() {
        settings.setAutoSend(true);
        service.runNow("test");

        resultArrives("AG_CONV_1", 0, "QK12ABC34D");
        resultArrives("AG_CONV_1", 0, "QK12ABC34D");

        assertThat(grace.getCommissionPaid()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("An agent with a payout in flight is skipped by the next run")
    void willNotStackPayouts() {
        settings.setAutoSend(true);
        service.runNow("test");

        // Their balance still reads as owed until the first one settles, which
        // is exactly how the same money gets sent twice.
        Map<String, Object> second = service.runNow("test");

        assertThat(second).containsEntry("queued", 0).containsEntry("skipped", 1);
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("With auto-send off the round is prepared but nothing leaves")
    void preparesWithoutSending() {
        settings.setAutoSend(false);

        Map<String, Object> result = service.runNow("test");

        assertThat(result).containsEntry("queued", 1).containsEntry("sent", 0);
        assertThat(stored.values().iterator().next().getStatus()).isEqualTo(CommissionPayout.Status.PENDING);
        verify(mpesa, never()).b2cPayment(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("A balance under the minimum rolls over instead of being sent")
    void leavesSmallBalancesAlone() {
        owes("120");
        assertThat(service.due()).isEmpty();
        assertThat(service.runNow("test")).containsEntry("queued", 0);
    }

    @Test
    @DisplayName("An agent owed more than the run's ceiling waits rather than being part-paid")
    void neverPartPays() {
        settings.setMaxPerRun(new BigDecimal("1000"));
        owes("2000");

        Map<String, Object> result = service.runNow("test");

        assertThat(result).containsEntry("queued", 0).containsEntry("skipped", 1);
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("An agent with no usable number is listed as blocked, not silently dropped")
    void saysWhyAnAgentCannotBePaid() {
        grace.setPhoneNumber("not a number");

        List<AgentPayoutService.Due> due = service.due();

        assertThat(due).hasSize(1);
        assertThat(due.get(0).blockedBecause()).contains("no usable M-Pesa number");
        assertThat(service.runNow("test")).containsEntry("queued", 0).containsEntry("skipped", 1);
    }

    @Test
    @DisplayName("Daraja refusing the request fails that payout without stopping the run")
    void recordsARefusal() {
        settings.setAutoSend(true);
        when(mpesa.b2cPayment(anyString(), any(), any(), anyString())).thenReturn(null);

        service.runNow("test");

        CommissionPayout p = stored.values().iterator().next();
        assertThat(p.getStatus()).isEqualTo(CommissionPayout.Status.FAILED);
        assertThat(p.getError()).contains("did not accept");
        assertThat(grace.getCommissionPaid()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Commission paid by hand is recorded in the same ledger")
    void recordsAManualPayment() {
        CommissionPayout p = service.recordManual(3L, new BigDecimal("750"), "ann");

        assertThat(p.getStatus()).isEqualTo(CommissionPayout.Status.MANUAL);
        assertThat(grace.getCommissionPaid()).isEqualByComparingTo("750");
    }

    @Test
    @DisplayName("A request Safaricom never answered is flagged, not marked failed")
    void flagsStaleWithoutGuessing() {
        settings.setAutoSend(true);
        service.runNow("test");
        CommissionPayout p = stored.values().iterator().next();
        p.setSentAt(Instant.now().minus(java.time.Duration.ofHours(9)));

        assertThat(service.flagStalePayouts()).isEqualTo(1);

        // Still SENT: the money may well have moved, and calling it failed
        // invites somebody to send it a second time.
        assertThat(p.getStatus()).isEqualTo(CommissionPayout.Status.SENT);
        assertThat(p.getError()).contains("No result from Safaricom");
        assertThat(service.flagStalePayouts()).isZero();
    }
}
