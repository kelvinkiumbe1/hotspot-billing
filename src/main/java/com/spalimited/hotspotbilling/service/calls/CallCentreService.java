package com.spalimited.hotspotbilling.service.calls;

import com.spalimited.hotspotbilling.domain.CallAgent;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.domain.CallRecord;
import com.spalimited.hotspotbilling.domain.CallSettings;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.CallAgentRepository;
import com.spalimited.hotspotbilling.repository.CallRecordRepository;
import com.spalimited.hotspotbilling.repository.CallSettingsRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A support phone line, on a number that belongs to the business.
 *
 * <p>Support currently happens on somebody's personal mobile, which costs three
 * things an operator feels without being able to see: customers ring whoever
 * helped them last rather than whoever is on duty, an agent's own number is out
 * in the world permanently, and there is no record of any of it.
 *
 * <h2>An outbound call is two legs, and that is not hideable</h2>
 *
 * <p>There is no way to make a customer's phone ring showing our number while an
 * agent's phone rings at the same moment. What actually happens when somebody
 * presses "call" is that we ring the AGENT, and when they answer we bridge them
 * through to the customer. So the agent's own phone rings first, before the
 * customer's does.
 *
 * <p>That is worth stating plainly everywhere it surfaces. An agent who does not
 * expect their own phone to ring assumes the button is broken and presses it
 * three more times, which is three more calls.
 *
 * <h2>Unverified against the real provider</h2>
 *
 * <p>No sandbox will place a real call, so the request shape below and the
 * callback handling are written from the API documentation. The XML half is
 * tested exactly, against a document builder with no dependencies; the
 * placing-a-call half is tested against a fake server at the socket level, which
 * proves what we send and not what they accept. One real call will confirm it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallCentreService {

    /**
     * How long an agent is held busy once a call is bridged to them.
     *
     * <p>A lease rather than a flag. What clears it is a callback saying the call
     * ended, and a callback that never arrives -- a provider outage, a dropped
     * webhook -- would otherwise leave an agent unreachable forever with nothing
     * on any screen to explain it. Two hours is longer than any support call and
     * short enough that a lost callback costs one afternoon rather than a week.
     */
    private static final Duration BUSY_LEASE = Duration.ofHours(2);

    private final CallSettingsRepository settingsRepo;
    private final CallAgentRepository agents;
    private final CallRecordRepository calls;
    private final SubscriberRepository subscribers;
    private final MessagingSettingsService messagingSettings;
    private final PhoneNumbers phoneNumbers;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    // --- Settings ---

    @Transactional
    public CallSettings settings() {
        return settingsRepo.findById(CallSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(CallSettings.builder()
                        .id(CallSettings.SINGLETON_ID).build()));
    }

    /**
     * The secret in the webhook URL, made on first use.
     *
     * <p>Generated here rather than in the migration so it is never the same on
     * two installations, and never printed in a SQL file somebody might commit.
     */
    @Transactional
    public String callbackToken() {
        CallSettings cfg = settings();
        if (cfg.getCallbackToken() == null || cfg.getCallbackToken().isBlank()) {
            byte[] bytes = new byte[24];
            new java.security.SecureRandom().nextBytes(bytes);
            cfg.setCallbackToken(java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes));
            settingsRepo.save(cfg);
        }
        return cfg.getCallbackToken();
    }

    /**
     * Whether a webhook request carries the right secret.
     *
     * <p>Constant-time, because a comparison that returns early leaks the token
     * one character at a time to anybody willing to make enough requests -- and
     * this endpoint is on the public internet by necessity.
     */
    @Transactional
    public boolean tokenMatches(String offered) {
        String expected = callbackToken();
        if (offered == null || offered.length() != expected.length()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                offered.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Regenerates the secret, which invalidates the URL the provider is using. */
    @Transactional
    public String rotateCallbackToken() {
        CallSettings cfg = settings();
        cfg.setCallbackToken(null);
        settingsRepo.save(cfg);
        return callbackToken();
    }

    @Transactional
    public CallSettings save(CallSettings incoming, String who) {
        CallSettings current = settings();
        current.setEnabled(incoming.isEnabled());
        current.setVirtualNumber(blankToNull(incoming.getVirtualNumber()));
        if (incoming.getVoiceBaseUrl() != null && !incoming.getVoiceBaseUrl().isBlank()) {
            current.setVoiceBaseUrl(incoming.getVoiceBaseUrl().strip());
        }
        current.setGreeting(blankToNull(incoming.getGreeting()));
        current.setNoAnswerMessage(blankToNull(incoming.getNoAnswerMessage()));
        current.setRecordCalls(incoming.isRecordCalls());
        current.setRingSeconds(Math.max(10, Math.min(120, incoming.getRingSeconds())));
        current.setUpdatedAt(Instant.now());
        current.setUpdatedBy(who);
        return settingsRepo.save(current);
    }

    /**
     * Whether the line can actually be used.
     *
     * <p>Three separate things have to be true, and the message says which is
     * missing rather than a bare "not configured" -- the commonest case by far is
     * an operator who switched it on and has not rented a number yet.
     */
    public String whyNotUsable() {
        CallSettings cfg = settings();
        if (!cfg.isEnabled()) {
            return "The phone line is switched off in settings.";
        }
        if (cfg.getVirtualNumber() == null || cfg.getVirtualNumber().isBlank()) {
            return "No virtual number is set. Rent one from your voice provider and enter it.";
        }
        var sms = messagingSettings.sms();
        if (sms.username() == null || sms.username().isBlank()
                || sms.apiKey() == null || sms.apiKey().isBlank()) {
            // Same credentials as SMS on purpose: an operator with working SMS
            // should not type an API key a second time.
            return "The voice line uses the same username and API key as SMS, and those are "
                    + "not set yet. Fill them in under Messaging.";
        }
        return null;
    }

    public boolean usable() {
        return whyNotUsable() == null;
    }

    // --- Agents ---

    @Transactional(readOnly = true)
    public List<CallAgent> allAgents() {
        return agents.findAllByOrderByPriorityAsc();
    }

    /**
     * Who to ring, in order.
     *
     * <p>Agents already on a call are left out rather than rung and found busy,
     * because a caller hearing four rings and then an engaged tone learns nothing
     * except that we are chaotic.
     */
    @Transactional(readOnly = true)
    public List<CallAgent> availableAgents() {
        List<CallAgent> free = new ArrayList<>();
        // Inbound rota only. A technician can place a call without being rung
        // by every customer who dials the business.
        for (CallAgent agent : agents.findByActiveTrueAndInboundTrueOrderByPriorityAsc()) {
            if (agent.isAvailable() && agent.getPhoneNumber() != null
                    && !agent.getPhoneNumber().isBlank()) {
                free.add(agent);
            }
        }
        return free;
    }

    /**
     * The agent identity a technician places calls under.
     *
     * <p>Created on first use rather than by an office chore nobody would
     * remember: a technician who cannot ring a customer falls back to their own
     * handset, which is the behaviour this exists to end.
     *
     * <p>Off the inbound rota, and the phone is re-read from the technician
     * record each time — a technician who changes their number would otherwise
     * keep being rung on the old one for as long as the agent row survives.
     */
    @Transactional
    public CallAgent agentForTechnician(Technician tech) {
        String phone = phoneNumbers.normalise(tech.getPhoneNumber());
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException(
                    "Your phone number is not on file, so we cannot ring you to start the call. "
                            + "Ask the office to add it.");
        }
        CallAgent agent = agents.findByTechnicianId(tech.getId()).orElseGet(() ->
                CallAgent.builder()
                        .technicianId(tech.getId())
                        .name(tech.getFullName())
                        .priority(50)
                        .active(true)
                        .inbound(false)
                        .createdAt(Instant.now())
                        .build());
        agent.setPhoneNumber(phone);
        agent.setName(tech.getFullName());
        agent.setInbound(false);
        agent.setActive(true);
        return agents.save(agent);
    }

    @Transactional
    public CallAgent saveAgent(CallAgent incoming) {
        if (incoming.getPhoneNumber() == null || incoming.getPhoneNumber().isBlank()) {
            throw new IllegalArgumentException("An agent needs a phone number to ring");
        }
        String normalised = phoneNumbers.normalise(incoming.getPhoneNumber());
        if (normalised == null || normalised.isBlank()) {
            throw new IllegalArgumentException(
                    "That does not look like a phone number this line can ring");
        }
        CallAgent agent = incoming.getId() == null ? new CallAgent()
                : agents.findById(incoming.getId())
                        .orElseThrow(() -> new IllegalArgumentException("No such agent"));
        agent.setName(incoming.getName());
        agent.setPhoneNumber(normalised);
        agent.setPriority(incoming.getPriority());
        agent.setActive(incoming.isActive());
        agent.setStaffId(incoming.getStaffId());
        return agents.save(agent);
    }

    @Transactional
    public void deleteAgent(Long id) {
        agents.deleteById(id);
    }

    /** Puts an agent back in the rota by hand, when a callback went missing. */
    @Transactional
    public CallAgent clearBusy(Long id) {
        CallAgent agent = agents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such agent"));
        agent.setBusyUntil(null);
        return agents.save(agent);
    }

    // --- Placing a call ---

    /** What a dial attempt did, in words a screen can show unchanged. */
    public record Dialled(boolean ok, String sessionId, String message) {
    }

    /**
     * Rings an agent, then bridges them to a customer.
     *
     * <p>The customer's number is not passed to the provider here. It is stored
     * on the call record and read back when the provider asks what to do with the
     * answered leg, which is what {@code answerOutbound} builds. Doing it that
     * way means the number the customer is dialled on comes from our database at
     * bridge time rather than from a URL, so nothing in the middle can redirect a
     * support call somewhere else.
     */
    @Transactional
    public Dialled dial(Long agentId, String customerNumber, Long subscriberId, Long ticketId) {
        String blocked = whyNotUsable();
        if (blocked != null) {
            return new Dialled(false, null, blocked);
        }
        CallAgent agent = agents.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("No such agent"));
        String customer = phoneNumbers.normalise(customerNumber);
        if (customer == null || customer.isBlank()) {
            return new Dialled(false, null, "That customer has no phone number to ring.");
        }

        CallSettings cfg = settings();
        var sms = messagingSettings.sms();
        String form = "username=" + encode(sms.username())
                + "&from=" + encode(cfg.getVirtualNumber())
                + "&to=" + encode(plus(agent.getPhoneNumber()));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(cfg.getVoiceBaseUrl()) + "/call"))
                    .header("apiKey", sms.apiKey())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return new Dialled(false, null,
                        "The voice provider refused the call (HTTP " + response.statusCode()
                                + "): " + response.body());
            }
            String sessionId = extract(response.body(), "sessionId");
            String status = extract(response.body(), "status");
            String error = extract(response.body(), "errorMessage");

            if (sessionId == null || sessionId.isBlank()) {
                // A 200 with no session is a refusal wearing a success code --
                // an unrented number, no credit, a malformed destination. The
                // provider's own words are more use than anything we could add.
                return new Dialled(false, null, "The call was not placed: "
                        + (error != null && !error.isBlank() ? error
                                : status != null ? status : response.body()));
            }

            calls.save(CallRecord.builder()
                    .sessionId(sessionId)
                    .direction(CallRecord.Direction.OUTBOUND)
                    .callerNumber(cfg.getVirtualNumber())
                    // The customer, stored now, dialled later. The agent's leg is
                    // just how we reach them.
                    .destinationNumber(customer)
                    .subscriberId(subscriberId)
                    .agentId(agent.getId())
                    .ticketId(ticketId)
                    .startedAt(Instant.now())
                    .status(CallRecord.Status.RINGING)
                    .build());
            markBusy(agent);

            log.info("Outbound call {} queued: ringing {} to reach {}",
                    sessionId, agent.getName(), customer);
            return new Dialled(true, sessionId,
                    "Ringing " + agent.getName() + " now. Answer that call and you will be "
                            + "connected to the customer.");
        } catch (Exception e) {
            log.warn("Could not place a call through the voice provider: {}", e.getMessage());
            return new Dialled(false, null, "Could not reach the voice provider: " + e.getMessage());
        }
    }

    // --- Callbacks ---

    /** Everything the provider sends us about one call, already flattened. */
    public record Callback(String sessionId, String direction, String callerNumber,
                           String destinationNumber, String status, String hangupCause,
                           String recordingUrl, Integer durationSeconds,
                           BigDecimal amount, String currency, boolean active) {
    }

    /**
     * What to do with an inbound call, and the record of it.
     *
     * <p>The caller is matched to a customer here, before anybody is rung, so the
     * account is already on screen when the agent says hello.
     */
    @Transactional
    public String answerInbound(Callback cb) {
        CallSettings cfg = settings();
        Optional<Subscriber> customer = whoIsCalling(cb.callerNumber());

        CallRecord record = calls.findBySessionId(cb.sessionId()).orElseGet(() ->
                CallRecord.builder()
                        .sessionId(cb.sessionId())
                        .direction(CallRecord.Direction.INBOUND)
                        .callerNumber(cb.callerNumber())
                        .destinationNumber(cb.destinationNumber())
                        .subscriberId(customer.map(Subscriber::getId).orElse(null))
                        .startedAt(Instant.now())
                        .status(CallRecord.Status.RINGING)
                        .build());

        if (!cfg.isEnabled()) {
            // Switched off entirely: reject rather than answer and apologise,
            // which would bill the operator for a call nobody wanted.
            record.setStatus(CallRecord.Status.FAILED);
            record.setHangupCause("The phone line is switched off");
            calls.save(record);
            return VoiceXml.reject();
        }

        List<CallAgent> free = availableAgents();
        if (free.isEmpty()) {
            // A customer who rang and got nobody is a callback somebody owes,
            // which is why this is MISSED and not FAILED.
            record.setStatus(CallRecord.Status.MISSED);
            record.setHangupCause("Nobody was available");
            calls.save(record);
            log.info("Inbound call from {} had nobody to answer it", cb.callerNumber());
            return VoiceXml.sayAndHangUp(cfg.getNoAnswerMessage());
        }

        // The first in the ring order gets the record, because that is who will
        // answer if anybody does. A later callback corrects it if the call rolls
        // on to somebody else.
        record.setAgentId(free.get(0).getId());
        calls.save(record);

        List<String> numbers = new ArrayList<>();
        for (CallAgent agent : free) {
            numbers.add(plus(agent.getPhoneNumber()));
        }
        return VoiceXml.dial(numbers, cfg.getGreeting(), cfg.isRecordCalls(), cfg.getRingSeconds());
    }

    /**
     * The agent has answered their leg. Bridge them to the customer.
     *
     * <p>The number comes from the stored call record rather than from anything
     * in the request, so a support call cannot be redirected by whatever the
     * provider echoes back at us.
     */
    @Transactional
    public String answerOutbound(Callback cb) {
        CallRecord record = calls.findBySessionId(cb.sessionId()).orElse(null);
        if (record == null || record.getDestinationNumber() == null) {
            // We did not place this call, or we have lost the customer's number.
            // Saying so is better than dialling something guessed.
            log.warn("Outbound callback for unknown session {}", cb.sessionId());
            return VoiceXml.sayAndHangUp("This call could not be connected. Please try again.");
        }
        CallSettings cfg = settings();
        record.setStatus(CallRecord.Status.ANSWERED);
        record.setAnsweredAt(Instant.now());
        calls.save(record);

        return VoiceXml.dial(List.of(plus(record.getDestinationNumber())),
                null, cfg.isRecordCalls(), cfg.getRingSeconds());
    }

    /** The call is over: cost, duration, recording, and the agent freed. */
    @Transactional
    public void finish(Callback cb) {
        calls.findBySessionId(cb.sessionId()).ifPresentOrElse(record -> {
            record.setEndedAt(Instant.now());
            record.setDurationSeconds(cb.durationSeconds());
            record.setHangupCause(cb.hangupCause());
            if (cb.recordingUrl() != null && !cb.recordingUrl().isBlank()) {
                record.setRecordingUrl(cb.recordingUrl());
            }
            record.setCost(cb.amount());
            record.setCurrency(cb.currency());
            // A call with seconds on the clock was answered by somebody, whatever
            // the provider says about how it ended.
            boolean talked = cb.durationSeconds() != null && cb.durationSeconds() > 0;
            if (talked) {
                record.setStatus(CallRecord.Status.COMPLETED);
                if (record.getAnsweredAt() == null) {
                    record.setAnsweredAt(record.getStartedAt());
                }
            } else if (record.getStatus() == CallRecord.Status.RINGING) {
                record.setStatus(record.getDirection() == CallRecord.Direction.INBOUND
                        ? CallRecord.Status.MISSED : CallRecord.Status.FAILED);
            }
            calls.save(record);

            if (record.getAgentId() != null) {
                agents.findById(record.getAgentId()).ifPresent(agent -> {
                    agent.setBusyUntil(null);
                    agents.save(agent);
                });
            }
            log.info("Call {} ended after {}s ({})", cb.sessionId(),
                    cb.durationSeconds(), cb.hangupCause());
        }, () -> log.warn("Completion callback for a call we have no record of: {}", cb.sessionId()));
    }

    // --- Reading ---

    /**
     * Who is on the phone, matched on the number they are calling from.
     *
     * <p>Only when exactly one customer has that number. Two customers sharing a
     * handset -- a household, a shop with two lines -- is common, and putting one
     * of their accounts on screen would have an agent confidently discussing the
     * wrong line.
     */
    @Transactional(readOnly = true)
    public Optional<Subscriber> whoIsCalling(String number) {
        String normalised = phoneNumbers.normalise(number);
        if (normalised == null || normalised.isBlank()) {
            return Optional.empty();
        }
        List<Subscriber> found = subscribers.findByPhoneNumber(normalised);
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<CallRecord> recent() {
        return calls.findTop200ByOrderByStartedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<CallRecord> forSubscriber(Long subscriberId) {
        return calls.findBySubscriberIdOrderByStartedAtDesc(subscriberId);
    }

    /** Calls in progress, for the screen that has to pop an account. */
    @Transactional(readOnly = true)
    public List<CallRecord> live() {
        return calls.findByStatusInOrderByStartedAtDesc(
                List.of(CallRecord.Status.RINGING, CallRecord.Status.ANSWERED));
    }

    @Transactional
    public CallRecord annotate(Long callId, String notes) {
        CallRecord record = calls.findById(callId)
                .orElseThrow(() -> new IllegalArgumentException("No such call"));
        record.setNotes(notes == null || notes.isBlank() ? null
                : notes.length() > 2000 ? notes.substring(0, 2000) : notes.strip());
        return calls.save(record);
    }

    /** Counts for the top of the page. */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Instant since = Instant.now().minus(Duration.ofDays(1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("missedToday", calls.countByStatusAndStartedAtAfter(CallRecord.Status.MISSED, since));
        out.put("completedToday",
                calls.countByStatusAndStartedAtAfter(CallRecord.Status.COMPLETED, since));
        out.put("live", live().size());
        out.put("agentsOnRota", agents.findByActiveTrueAndInboundTrueOrderByPriorityAsc().size());
        out.put("agentsFree", availableAgents().size());
        return out;
    }

    // --- Plumbing ---

    private void markBusy(CallAgent agent) {
        agent.setBusyUntil(Instant.now().plus(BUSY_LEASE));
        agents.save(agent);
    }

    /**
     * Pulls one value out of the provider's JSON without a parser.
     *
     * <p>Three fields are needed from a response of six, and every other
     * integration in this codebase that reached for a JSON library for a job this
     * size regretted the dependency more than the shortcut. Deliberately
     * tolerant: a field that is missing returns null, which every caller handles.
     */
    static String extract(String json, String field) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return end < 0 ? null : json.substring(i + 1, end);
        }
        int end = i;
        while (end < json.length() && ",}] \n\r\t".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        String value = json.substring(i, end);
        return "null".equals(value) ? null : value;
    }

    /** The provider wants E.164 with the plus; we store without it. */
    static String plus(String number) {
        if (number == null || number.isBlank()) {
            return "";
        }
        String trimmed = number.strip();
        return trimmed.startsWith("+") ? trimmed : "+" + trimmed;
    }

    private static String trimSlash(String url) {
        String s = url == null ? "" : url.strip();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
