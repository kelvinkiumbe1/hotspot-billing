package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.AiSettings;
import com.spalimited.hotspotbilling.domain.Incident;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.TicketMessage;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The half of the reply copilot that is not the model: which facts get put in
 * front of it. Getting these wrong is how a draft comes to apologise for an
 * outage the customer is not in, or to ask a paid-up customer to pay.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketDraftServiceTest {

    @Mock private SupportTicketRepository tickets;
    @Mock private SubscriberRepository subscribers;
    @Mock private VoucherRepository vouchers;
    @Mock private RouterRepository routers;
    @Mock private IncidentRepository incidents;
    @Mock private AiService ai;
    @Mock private AiSettingsService aiSettings;
    @Mock private PortalSettingsService portalSettings;

    private TicketDraftService service;

    private final Map<Long, SupportTicket> stored = new LinkedHashMap<>();
    private final List<Incident> openIncidents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new TicketDraftService(tickets, subscribers, vouchers, routers, incidents,
                ai, aiSettings, portalSettings);

        when(portalSettings.settings()).thenReturn(PortalSettings.builder().businessName("SPA WiFi").build());
        when(aiSettings.get()).thenReturn(AiSettings.builder()
                .id(1L).enabled(true).apiKey("gsk_test").draftTicketReplies(true).build());
        when(ai.isEnabled()).thenReturn(true);
        when(ai.chat(anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn("Sorry about that — we're on it.");

        when(tickets.findById(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(tickets.save(any(SupportTicket.class))).thenAnswer(i -> {
            SupportTicket t = i.getArgument(0);
            stored.put(t.getId(), t);
            return t;
        });
        when(tickets.findTop100ByOrderByUpdatedAtDesc()).thenAnswer(i -> List.copyOf(stored.values()));
        when(subscribers.findByPhoneNumber(anyString())).thenReturn(List.of());
        when(vouchers.findByPhoneNumberOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(routers.findByEnabledTrue()).thenReturn(List.of());
        when(incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN)).thenReturn(openIncidents);
    }

    private SupportTicket ticket(long id, String subject, SupportTicket.Status status, String customerSays) {
        SupportTicket t = SupportTicket.builder()
                .id(id)
                .customerName("Jane Doe")
                .phoneNumber("0733111222")
                .subject(subject)
                .status(status)
                .createdAt(Instant.now().minus(Duration.ofMinutes(10)))
                .updatedAt(Instant.now().minus(Duration.ofMinutes(10)))
                .build();
        if (customerSays != null) {
            t.getMessages().add(TicketMessage.builder().ticket(t).fromAdmin(false)
                    .body(customerSays).createdAt(Instant.now()).build());
        }
        stored.put(id, t);
        return t;
    }

    private void subscriberOn(String phone, Instant paidUntil, Long routerId) {
        Subscriber s = Subscriber.builder()
                .id(1L).fullName("Jane Doe").phoneNumber(phone)
                .status(Subscriber.Status.ACTIVE).paidUntil(paidUntil).routerId(routerId)
                .bandwidth("10Mbps").createdAt(Instant.now()).build();
        when(subscribers.findByPhoneNumber(phone)).thenReturn(List.of(s));
    }

    @Test
    @DisplayName("An expired subscriber is flagged, because that alone explains a dead line")
    void flagsAnExpiredSubscriber() {
        ticket(1L, "No internet since morning", SupportTicket.Status.OPEN, "Nothing is working");
        subscriberOn("254733111222", Instant.now().minus(Duration.ofDays(4)), null);

        List<String> basis = service.draft(1L).basis();

        assertThat(basis).anyMatch(b -> b.contains("expired on"));
        assertThat(basis).anyMatch(b -> b.contains("not currently paid"));
    }

    @Test
    @DisplayName("A paid-up subscriber is never described as owing anything")
    void doesNotAccuseAPaidCustomer() {
        ticket(1L, "Slow speeds in the evening", SupportTicket.Status.OPEN, "It crawls after 8pm");
        subscriberOn("254733111222", Instant.now().plus(Duration.ofDays(20)), null);

        List<String> basis = service.draft(1L).basis();

        assertThat(basis).anyMatch(b -> b.contains("paid until"));
        assertThat(basis).noneMatch(b -> b.contains("not currently paid"));
    }

    @Test
    @DisplayName("An outage is only 'theirs' when their own router is in it")
    void distinguishesWhoseOutageItIs() {
        ticket(1L, "No internet", SupportTicket.Status.OPEN, "Dead since 9");
        subscriberOn("254733111222", Instant.now().plus(Duration.ofDays(10)), 5L);
        when(routers.findById(5L)).thenReturn(Optional.of(
                Router.builder().id(5L).name("Kilimani AP").online(true).build()));

        Incident outage = Incident.builder().id(1L).status(Incident.Status.OPEN)
                .title("Kasarani down").startedAt(Instant.now().minus(Duration.ofMinutes(40))).build();
        outage.getRouterIds().add(9L);
        openIncidents.add(outage);

        assertThat(service.draft(1L).basis())
                .anyMatch(b -> b.contains("does not appear to be in the affected area"));

        // Now the same outage takes in the router this customer is actually on.
        outage.getRouterIds().add(5L);
        assertThat(service.draft(1L).basis())
                .anyMatch(b -> b.contains("THIS CUSTOMER IS AFFECTED"));
    }

    @Test
    @DisplayName("What closed similar tickets before is put in front of the agent")
    void surfacesWhatFixedThisLastTime() {
        SupportTicket old = ticket(1L, "No internet in Kilimani", SupportTicket.Status.RESOLVED, "Dead");
        old.getMessages().add(TicketMessage.builder().ticket(old).fromAdmin(true)
                .body("Ann: the AP had lost power, plugged it back in").createdAt(Instant.now()).build());
        ticket(2L, "No internet again", SupportTicket.Status.OPEN, "Same as last week");

        List<String> basis = service.draft(2L).basis();

        assertThat(basis).anyMatch(b -> b.contains("What fixed tickets like this before"));
        assertThat(basis).anyMatch(b -> b.contains("lost power"));
    }

    @Test
    @DisplayName("Common words alone are not a match — 'the' does not make two tickets alike")
    void doesNotMatchOnFiller() {
        SupportTicket old = ticket(1L, "Please help with the router", SupportTicket.Status.RESOLVED, "x");
        old.getMessages().add(TicketMessage.builder().ticket(old).fromAdmin(true)
                .body("Ann: swapped the router").createdAt(Instant.now()).build());
        ticket(2L, "Please help with the invoice", SupportTicket.Status.OPEN, "y");

        assertThat(service.draft(2L).basis())
                .noneMatch(b -> b.contains("What fixed tickets like this before"));
    }

    @Test
    @DisplayName("When the model fails the facts still come back, and the failure is stamped")
    void survivesTheModelBeingDown() {
        ticket(1L, "No internet", SupportTicket.Status.OPEN, "Dead");
        when(ai.chat(anyString(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("The assistant is off"));

        TicketDraftService.Draft d = service.draft(1L);

        assertThat(d.drafted()).isFalse();
        assertThat(d.error()).contains("assistant is off");
        assertThat(d.basis()).isNotEmpty();
        // Stamped even though it failed, so the sweep does not retry it forever.
        assertThat(stored.get(1L).getAiDraftTriedAt()).isNotNull();
        assertThat(stored.get(1L).getAiDraft()).isNull();
    }

    @Test
    @DisplayName("A ticket somebody has already answered is left alone")
    void skipsTicketsAlreadyAnswered() {
        SupportTicket answered = ticket(1L, "No internet", SupportTicket.Status.IN_PROGRESS, "Dead");
        answered.getMessages().add(TicketMessage.builder().ticket(answered).fromAdmin(true)
                .body("On our way").createdAt(Instant.now()).build());

        assertThat(service.draftPending(5)).isZero();
        verify(ai, never()).chat(anyString(), anyString(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("Drafting off means nothing is sent to the model at all")
    void respectsTheOffSwitch() {
        ticket(1L, "No internet", SupportTicket.Status.OPEN, "Dead");
        when(aiSettings.get()).thenReturn(AiSettings.builder()
                .id(1L).enabled(true).apiKey("gsk_test").draftTicketReplies(false).build());

        assertThat(service.draftPending(5)).isZero();
        verify(ai, never()).chat(anyString(), anyString(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("The model is told to use the facts and invent nothing")
    void groundsThePrompt() {
        ticket(1L, "No internet", SupportTicket.Status.OPEN, "Dead since morning");
        subscriberOn("254733111222", Instant.now().minus(Duration.ofDays(2)), null);

        service.draft(1L);

        verify(ai).chat(contains("Never state anything that is not in them"),
                contains("expired on"), anyInt(), anyDouble());
    }
}
