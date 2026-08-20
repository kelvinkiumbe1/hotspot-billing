package com.spalimited.hotspotbilling.service.calls;

import com.spalimited.hotspotbilling.domain.CallAgent;
import com.spalimited.hotspotbilling.domain.CallRecord;
import com.spalimited.hotspotbilling.domain.CallSettings;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.CallAgentRepository;
import com.spalimited.hotspotbilling.repository.CallRecordRepository;
import com.spalimited.hotspotbilling.repository.CallSettingsRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The support phone line.
 *
 * <p>Two halves, tested two ways. Placing a call goes over a real socket to a
 * fake provider, because the request has never once been executed against the
 * real API and a mocked HTTP client would prove only that our parsing matches
 * our own fixture. Answering a call is XML, which is exactly right or exactly
 * wrong and needs no network at all.
 *
 * <p>What none of this proves is that Africa's Talking accepts what we send.
 * That takes one real call.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallCentreServiceTest {

    @Mock
    private CallSettingsRepository settingsRepo;

    @Mock
    private CallAgentRepository agentRepo;

    @Mock
    private CallRecordRepository callRepo;

    @Mock
    private SubscriberRepository subscribers;

    @Mock
    private MessagingSettingsService messagingSettings;

    @Mock
    private PhoneNumbers phoneNumbers;

    @InjectMocks
    private CallCentreService service;

    private FakeVoiceApi provider;
    private CallSettings cfg;
    private CallAgent grace;
    private CallAgent peter;
    private final List<CallRecord> stored = new ArrayList<>();
    private List<CallAgent> rota;

    @BeforeEach
    void setUp() {
        provider = new FakeVoiceApi();
        stored.clear();

        cfg = CallSettings.builder()
                .id(CallSettings.SINGLETON_ID)
                .enabled(true)
                .virtualNumber("+254203000000")
                .voiceBaseUrl(provider.url())
                .greeting("Karibu, please hold.")
                .noAnswerMessage("Everyone is busy. We will call you back.")
                .recordCalls(false)
                .ringSeconds(25)
                .build();
        when(settingsRepo.findById(CallSettings.SINGLETON_ID)).thenReturn(Optional.of(cfg));
        when(settingsRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        grace = CallAgent.builder().id(1L).name("Grace").phoneNumber("254700000001")
                .priority(1).active(true).build();
        peter = CallAgent.builder().id(2L).name("Peter").phoneNumber("254700000002")
                .priority(2).active(true).build();
        rota = new ArrayList<>(List.of(grace, peter));
        when(agentRepo.findByActiveTrueOrderByPriorityAsc()).thenAnswer(i ->
                rota.stream().filter(CallAgent::isActive).toList());
        when(agentRepo.findById(1L)).thenReturn(Optional.of(grace));
        when(agentRepo.findById(2L)).thenReturn(Optional.of(peter));
        when(agentRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        when(messagingSettings.sms()).thenReturn(new MessagingSettingsService.SmsConfig(
                true, "AFRICASTALKING", "spawifi", "atsk_secret", "SPAWIFI",
                "https://api.africastalking.com"));

        when(phoneNumbers.normalise(anyString())).thenAnswer(i -> {
            String raw = i.getArgument(0);
            if (raw == null) {
                return null;
            }
            String digits = raw.replaceAll("\\D", "");
            if (digits.startsWith("0") && digits.length() == 10) {
                return "254" + digits.substring(1);
            }
            return digits.isBlank() ? null : digits;
        });

        when(callRepo.save(any())).thenAnswer(i -> {
            CallRecord r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId((long) (stored.size() + 1));
                stored.add(r);
            }
            return r;
        });
        when(callRepo.findBySessionId(anyString())).thenAnswer(i ->
                stored.stream().filter(r -> r.getSessionId().equals(i.getArgument(0))).findFirst());
        when(callRepo.findById(anyLong())).thenAnswer(i ->
                stored.stream().filter(r -> r.getId().equals(i.getArgument(0))).findFirst());

        Subscriber mary = Subscriber.builder().id(42L).fullName("Mary Kamau")
                .phoneNumber("254712345678").pppoeUsername("mkamau")
                .monthlyFee(new BigDecimal("2500")).build();
        when(subscribers.findById(42L)).thenReturn(Optional.of(mary));
        when(subscribers.findByPhoneNumber("254712345678")).thenReturn(List.of(mary));
        when(subscribers.findByPhoneNumber(anyString())).thenAnswer(i ->
                "254712345678".equals(i.getArgument(0)) ? List.of(mary) : List.of());
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    // --- Placing a call ---

    @Test
    @DisplayName("dialling rings the agent, from our number, with the API key on the header")
    void dialSendsTheRightRequest() {
        provider.on("/call", "{\"entries\":[{\"phoneNumber\":\"+254700000001\","
                + "\"status\":\"Queued\",\"sessionId\":\"ATVId_abc123\"}]}");

        CallCentreService.Dialled result = service.dial(1L, "0712345678", 42L, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.sessionId()).isEqualTo("ATVId_abc123");

        FakeVoiceApi.Call sent = provider.call("/call");
        assertThat(sent.method()).isEqualTo("POST");
        // The header the SMS integration already uses. Getting this wrong is a
        // 401 that reads as "the number is wrong".
        assertThat(sent.header("apiKey")).isEqualTo("atsk_secret");
        assertThat(sent.header("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(sent.field("username")).isEqualTo("spawifi");
        assertThat(sent.field("from")).isEqualTo("+254203000000");
        // The AGENT is rung, not the customer. The customer is dialled on the
        // second leg once the agent has picked up.
        assertThat(sent.field("to")).isEqualTo("+254700000001");
    }

    @Test
    @DisplayName("the message tells the agent their own phone will ring first")
    void dialExplainsTheTwoLegs() {
        provider.on("/call", "{\"entries\":[{\"sessionId\":\"ATVId_1\",\"status\":\"Queued\"}]}");

        CallCentreService.Dialled result = service.dial(1L, "0712345678", 42L, null);

        // An agent who does not expect this assumes the button is broken and
        // presses it three more times.
        assertThat(result.message()).contains("Ringing Grace");
        assertThat(result.message()).contains("Answer that call");
    }

    @Test
    @DisplayName("the call is recorded against the customer, with their number kept for the bridge")
    void dialStoresTheCustomerNumber() {
        provider.on("/call", "{\"entries\":[{\"sessionId\":\"ATVId_2\",\"status\":\"Queued\"}]}");

        service.dial(1L, "0712345678", 42L, 9L);

        CallRecord record = stored.get(0);
        assertThat(record.getDirection()).isEqualTo(CallRecord.Direction.OUTBOUND);
        assertThat(record.getSubscriberId()).isEqualTo(42L);
        assertThat(record.getAgentId()).isEqualTo(1L);
        assertThat(record.getTicketId()).isEqualTo(9L);
        assertThat(record.getStatus()).isEqualTo(CallRecord.Status.RINGING);
        // Normalised and stored, so the bridge dials our database rather than
        // whatever the provider echoes back at us.
        assertThat(record.getDestinationNumber()).isEqualTo("254712345678");
    }

    @Test
    @DisplayName("a 200 with no session id is a refusal, not a success")
    void twoHundredWithNoSessionIsAFailure() {
        // Real shape of an unrented number or an account with no credit.
        provider.on("/call", "{\"errorMessage\":\"Insufficient credit\",\"entries\":[]}");

        CallCentreService.Dialled result = service.dial(1L, "0712345678", 42L, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Insufficient credit");
        // Nothing stored: there is no call to have a record of.
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("an HTTP error is reported with the provider's own words")
    void httpErrorIsSurfaced() {
        provider.on("/call", 401, "{\"errorMessage\":\"Invalid credentials\"}");

        CallCentreService.Dialled result = service.dial(1L, "0712345678", 42L, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("401");
        assertThat(result.message()).contains("Invalid credentials");
    }

    @Test
    @DisplayName("the line being switched off says so instead of dialling")
    void disabledLineExplainsItself() {
        cfg.setEnabled(false);

        CallCentreService.Dialled result = service.dial(1L, "0712345678", 42L, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("switched off");
        assertThat(provider.calls()).isEmpty();
    }

    @Test
    @DisplayName("no virtual number names that as the thing missing")
    void missingNumberIsNamed() {
        cfg.setVirtualNumber(null);

        // "Not configured" would send somebody hunting through four screens.
        assertThat(service.whyNotUsable()).contains("virtual number");
    }

    @Test
    @DisplayName("dialling holds the agent so the next caller is not sent to them")
    void dialMarksTheAgentBusy() {
        provider.on("/call", "{\"entries\":[{\"sessionId\":\"ATVId_3\",\"status\":\"Queued\"}]}");

        service.dial(1L, "0712345678", 42L, null);

        assertThat(grace.isAvailable()).isFalse();
        assertThat(service.availableAgents()).extracting(CallAgent::getName)
                .containsExactly("Peter");
    }

    // --- Inbound ---

    @Test
    @DisplayName("an incoming call greets the caller and rings the rota in order")
    void inboundRingsInOrder() {
        String xml = service.answerInbound(inbound("ATVId_in1", "254712345678"));

        assertThat(xml).contains("Karibu, please hold.");
        assertThat(xml).contains("phoneNumbers=\"+254700000001,+254700000002\"");
    }

    @Test
    @DisplayName("the caller is matched to their account before anybody picks up")
    void inboundMatchesTheCaller() {
        service.answerInbound(inbound("ATVId_in2", "254712345678"));

        // This is what puts the account on screen as the phone rings, rather
        // than after the agent has asked for a name and spelled it wrong.
        assertThat(stored.get(0).getSubscriberId()).isEqualTo(42L);
        assertThat(stored.get(0).getDirection()).isEqualTo(CallRecord.Direction.INBOUND);
    }

    @Test
    @DisplayName("an unknown number is still logged, just without a customer")
    void inboundFromAStranger() {
        service.answerInbound(inbound("ATVId_in3", "254799999999"));

        assertThat(stored.get(0).getSubscriberId()).isNull();
        assertThat(stored.get(0).getCallerNumber()).isEqualTo("254799999999");
    }

    @Test
    @DisplayName("two customers on one number is no match rather than a guess")
    void ambiguousCallerIsNotGuessed() {
        Subscriber a = Subscriber.builder().id(50L).fullName("Shop Line One")
                .phoneNumber("254733000000").build();
        Subscriber b = Subscriber.builder().id(51L).fullName("Shop Line Two")
                .phoneNumber("254733000000").build();
        when(subscribers.findByPhoneNumber("254733000000")).thenReturn(List.of(a, b));

        service.answerInbound(inbound("ATVId_in4", "254733000000"));

        // An agent confidently discussing the wrong line is worse than one who
        // has to ask which.
        assertThat(stored.get(0).getSubscriberId()).isNull();
    }

    @Test
    @DisplayName("nobody on the rota means an apology, and a missed call somebody owes")
    void nobodyAvailable() {
        grace.setActive(false);
        peter.setActive(false);

        String xml = service.answerInbound(inbound("ATVId_in5", "254712345678"));

        assertThat(xml).contains("Everyone is busy");
        assertThat(xml).doesNotContain("<Dial");
        // MISSED, not FAILED: this is a callback owed, not a broken config.
        assertThat(stored.get(0).getStatus()).isEqualTo(CallRecord.Status.MISSED);
    }

    @Test
    @DisplayName("an agent already on a call is not rung again")
    void busyAgentIsSkipped() {
        grace.setBusyUntil(Instant.now().plusSeconds(600));

        String xml = service.answerInbound(inbound("ATVId_in6", "254712345678"));

        assertThat(xml).contains("phoneNumbers=\"+254700000002\"");
        assertThat(xml).doesNotContain("254700000001");
    }

    @Test
    @DisplayName("a call arriving while the line is off is rejected rather than answered")
    void inboundWhileDisabled() {
        cfg.setEnabled(false);

        String xml = service.answerInbound(inbound("ATVId_in7", "254712345678"));

        // Answering and apologising would bill the operator for a call they did
        // not want to take.
        assertThat(xml).contains("<Reject/>");
        assertThat(stored.get(0).getStatus()).isEqualTo(CallRecord.Status.FAILED);
    }

    @Test
    @DisplayName("a stale busy lease expires so a lost callback does not strand an agent")
    void busyLeaseExpires() {
        grace.setBusyUntil(Instant.now().minusSeconds(1));

        assertThat(grace.isAvailable()).isTrue();
    }

    // --- Bridging and finishing ---

    @Test
    @DisplayName("when the agent answers, the customer is dialled from our own record")
    void outboundBridgesToTheStoredNumber() {
        provider.on("/call", "{\"entries\":[{\"sessionId\":\"ATVId_out1\",\"status\":\"Queued\"}]}");
        service.dial(1L, "0712345678", 42L, null);

        String xml = service.answerOutbound(outbound("ATVId_out1"));

        assertThat(xml).contains("phoneNumbers=\"+254712345678\"");
        assertThat(stored.get(0).getStatus()).isEqualTo(CallRecord.Status.ANSWERED);
        assertThat(stored.get(0).getAnsweredAt()).isNotNull();
    }

    @Test
    @DisplayName("a bridge callback for a call we never placed does not dial anything")
    void outboundForUnknownSession() {
        String xml = service.answerOutbound(outbound("ATVId_never"));

        assertThat(xml).doesNotContain("<Dial");
        assertThat(xml).contains("could not be connected");
    }

    @Test
    @DisplayName("finishing records the duration, cost and recording, and frees the agent")
    void finishStoresTheOutcome() {
        provider.on("/call", "{\"entries\":[{\"sessionId\":\"ATVId_out2\",\"status\":\"Queued\"}]}");
        service.dial(1L, "0712345678", 42L, null);
        assertThat(grace.isAvailable()).isFalse();

        service.finish(new CallCentreService.Callback("ATVId_out2", "Outbound",
                "+254203000000", "+254712345678", "Completed", "NormalClearing",
                "https://recordings.example/abc.wav", 143, new BigDecimal("1.2000"), "KES", false));

        CallRecord record = stored.get(0);
        assertThat(record.getStatus()).isEqualTo(CallRecord.Status.COMPLETED);
        assertThat(record.getDurationSeconds()).isEqualTo(143);
        assertThat(record.getCost()).isEqualByComparingTo("1.2000");
        assertThat(record.getRecordingUrl()).contains("abc.wav");
        assertThat(record.getHangupCause()).isEqualTo("NormalClearing");
        // The whole point of the lease being cleared: the next caller can reach
        // Grace again.
        assertThat(grace.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("a call with seconds on the clock counts as answered whatever the cause says")
    void durationBeatsTheHangupCause() {
        service.answerInbound(inbound("ATVId_in8", "254712345678"));

        service.finish(new CallCentreService.Callback("ATVId_in8", "Inbound",
                "254712345678", "+254203000000", "Completed", "UserBusy",
                null, 62, new BigDecimal("0.5"), "KES", false));

        // Somebody talked for a minute. Trusting a hangup cause over the clock
        // would file a real conversation as a missed call.
        assertThat(stored.get(0).getStatus()).isEqualTo(CallRecord.Status.COMPLETED);
    }

    @Test
    @DisplayName("an inbound call nobody picked up becomes missed, not failed")
    void unansweredInboundIsMissed() {
        service.answerInbound(inbound("ATVId_in9", "254712345678"));

        service.finish(new CallCentreService.Callback("ATVId_in9", "Inbound",
                "254712345678", "+254203000000", "Completed", "NoAnswer",
                null, 0, BigDecimal.ZERO, "KES", false));

        assertThat(stored.get(0).getStatus()).isEqualTo(CallRecord.Status.MISSED);
    }

    @Test
    @DisplayName("an unanswered outbound call is a failure, not a missed one")
    void unansweredOutboundIsFailed() {
        provider.on("/call", "{\"entries\":[{\"sessionId\":\"ATVId_out3\",\"status\":\"Queued\"}]}");
        service.dial(1L, "0712345678", 42L, null);

        service.finish(new CallCentreService.Callback("ATVId_out3", "Outbound",
                "+254203000000", "+254712345678", "Completed", "NoAnswer",
                null, 0, BigDecimal.ZERO, "KES", false));

        // Nobody is owed a callback: it was us doing the calling.
        assertThat(stored.get(0).getStatus()).isEqualTo(CallRecord.Status.FAILED);
    }

    // --- The webhook secret ---

    @Test
    @DisplayName("the callback token is generated once and then stays put")
    void tokenIsStable() {
        cfg.setCallbackToken(null);

        String first = service.callbackToken();
        String second = service.callbackToken();

        assertThat(first).hasSizeGreaterThan(20);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("only the right token is accepted")
    void tokenIsChecked() {
        cfg.setCallbackToken(null);
        String token = service.callbackToken();

        assertThat(service.tokenMatches(token)).isTrue();
        assertThat(service.tokenMatches(token.substring(0, token.length() - 1) + "X")).isFalse();
        assertThat(service.tokenMatches("")).isFalse();
        assertThat(service.tokenMatches(null)).isFalse();
    }

    @Test
    @DisplayName("rotating the token invalidates the old one")
    void tokenRotates() {
        cfg.setCallbackToken(null);
        String old = service.callbackToken();

        String fresh = service.rotateCallbackToken();

        assertThat(fresh).isNotEqualTo(old);
        assertThat(service.tokenMatches(old)).isFalse();
        assertThat(service.tokenMatches(fresh)).isTrue();
    }

    // --- The JSON reader ---

    @Test
    @DisplayName("a field is read out of the provider's JSON wherever it sits")
    void extractReadsFields() {
        String json = "{\"entries\":[{\"phoneNumber\":\"+254700000001\",\"status\":\"Queued\","
                + "\"sessionId\":\"ATVId_x\"}],\"errorMessage\":\"None\"}";

        assertThat(CallCentreService.extract(json, "sessionId")).isEqualTo("ATVId_x");
        assertThat(CallCentreService.extract(json, "status")).isEqualTo("Queued");
        assertThat(CallCentreService.extract(json, "errorMessage")).isEqualTo("None");
        assertThat(CallCentreService.extract(json, "missing")).isNull();
        assertThat(CallCentreService.extract(null, "sessionId")).isNull();
    }

    @Test
    @DisplayName("a null in the JSON reads as absent rather than as the word null")
    void extractHandlesNull() {
        assertThat(CallCentreService.extract("{\"sessionId\":null}", "sessionId")).isNull();
    }

    @Test
    @DisplayName("numbers stored without a plus get one on the way out")
    void plusIsAdded() {
        assertThat(CallCentreService.plus("254700000001")).isEqualTo("+254700000001");
        assertThat(CallCentreService.plus("+254700000001")).isEqualTo("+254700000001");
        assertThat(CallCentreService.plus(null)).isEmpty();
    }

    // --- Helpers ---

    private static CallCentreService.Callback inbound(String sessionId, String from) {
        return new CallCentreService.Callback(sessionId, "Inbound", from, "+254203000000",
                "Ringing", null, null, null, null, null, true);
    }

    private static CallCentreService.Callback outbound(String sessionId) {
        return new CallCentreService.Callback(sessionId, "Outbound", "+254203000000",
                "+254700000001", "Answered", null, null, null, null, null, true);
    }
}
