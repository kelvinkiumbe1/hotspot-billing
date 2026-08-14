package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The outage lifecycle, which cannot be exercised against a real router without
 * a real router to unplug: grouping simultaneous failures, holding off on the
 * customer notice until a drop has proved it is not a blip, refusing to call an
 * all-clear while part of the network is still down, and crediting only the
 * people who were actually affected.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentServiceTest {

    @Mock private IncidentRepository incidents;
    @Mock private RouterRepository routers;
    @Mock private VoucherRepository vouchers;
    @Mock private SupportTicketRepository tickets;
    @Mock private SubscriptionService subscriptions;
    @Mock private OperatorAlertSettingsService alertSettings;
    @Mock private PortalSettingsService portalSettings;
    @Mock private SmsService smsService;
    @Mock private AuditService audit;

    private IncidentService service;

    /** A tiny stand-in store, so the lifecycle can actually be played through. */
    private final Map<Long, Incident> stored = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    private Router westlands;
    private Router kasarani;
    private OperatorAlertSettings settings;

    @BeforeEach
    void setUp() {
        service = new IncidentService(incidents, routers, vouchers, tickets, subscriptions,
                alertSettings, portalSettings, smsService, audit);

        westlands = router(1L, "Westlands Site", "Westlands");
        kasarani = router(2L, "Kasarani Site", "Kasarani");

        settings = OperatorAlertSettings.builder()
                .id(1L)
                .customerOutageNotice(true)
                .outageNotifyAfterMinutes(10)
                .outageEtaMinutes(120)
                .outageCompensationEnabled(true)
                .minOutageMinutes(30)
                .statusPageEnabled(true)
                .build();
        when(alertSettings.get()).thenReturn(settings);
        when(portalSettings.settings()).thenReturn(PortalSettings.builder().businessName("SPA WiFi").build());
        when(vouchers.findByStatusIn(anyCollection())).thenReturn(List.of());
        when(subscriptions.affectedBy(anySet())).thenReturn(List.of());

        when(incidents.save(any(Incident.class))).thenAnswer(call -> {
            Incident i = call.getArgument(0);
            if (i.getId() == null) {
                i.setId(ids.getAndIncrement());
            }
            stored.put(i.getId(), i);
            return i;
        });
        when(incidents.findFirstByStatusOrderByStartedAtDesc(any())).thenAnswer(call ->
                stored.values().stream()
                        .filter(i -> i.getStatus() == call.getArgument(0))
                        .max(Comparator.comparing(Incident::getStartedAt)));
        when(incidents.findByStatusOrderByStartedAtDesc(any())).thenAnswer(call ->
                stored.values().stream()
                        .filter(i -> i.getStatus() == call.getArgument(0))
                        .sorted(Comparator.comparing(Incident::getStartedAt).reversed())
                        .toList());
        when(routers.findById(1L)).thenReturn(Optional.of(westlands));
        when(routers.findById(2L)).thenReturn(Optional.of(kasarani));
    }

    private Router router(Long id, String name, String location) {
        Router r = new Router();
        r.setId(id);
        r.setName(name);
        r.setLocation(location);
        r.setEnabled(true);
        r.setOnline(false);
        return r;
    }

    @Test
    @DisplayName("routers failing together become one incident, not two")
    void groupsSimultaneousFailures() {
        Incident first = service.routerDown(westlands);
        Incident second = service.routerDown(kasarani);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(stored).hasSize(1);
        assertThat(second.getRouterIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(second.getTitle()).contains("2 sites are down");
    }

    @Test
    @DisplayName("a failure long after the last one is its own incident")
    void separatesUnrelatedFailures() {
        Incident first = service.routerDown(westlands);
        // Age the first one past the grouping window.
        first.setStartedAt(Instant.now().minus(Duration.ofHours(2)));

        Incident second = service.routerDown(kasarani);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("customers are not told about a blip")
    void staysQuietUntilTheOutageHasLasted() {
        service.routerDown(westlands);

        service.notifyRipeIncidents();

        verifyNoInteractions(smsService);
        assertThat(stored.values().iterator().next().getNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("only the customers on the affected routers are told, once")
    void notifiesAffectedCustomersOnce() {
        when(subscriptions.affectedBy(anySet())).thenReturn(List.of(
                subscriber("Asha", "254700000001"),
                subscriber("Brian", "254700000002")));
        when(tickets.save(any(SupportTicket.class))).thenAnswer(call -> {
            SupportTicket t = call.getArgument(0);
            t.setId(99L);
            return t;
        });

        Incident incident = service.routerDown(westlands);
        incident.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));

        service.notifyRipeIncidents();
        service.notifyRipeIncidents(); // a second sweep must not message them again

        verify(smsService, times(1)).trySend(eq("254700000001"), contains("Westlands"));
        verify(smsService, times(1)).trySend(eq("254700000002"), anyString());
        assertThat(incident.getNotifiedCount()).isEqualTo(2);
        assertThat(incident.getTicketId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("a half-recovered outage is not an all-clear")
    void doesNotResolveWhileRoutersAreStillDown() {
        service.routerDown(westlands);
        service.routerDown(kasarani);

        westlands.setOnline(true); // one back, one still down
        service.routerUp(westlands);

        Incident incident = stored.values().iterator().next();
        assertThat(incident.getStatus()).isEqualTo(Incident.Status.OPEN);
        verify(subscriptions, never()).compensateForOutage(any(), any());
    }

    @Test
    @DisplayName("full recovery closes it, credits the affected and says so")
    void resolvesAndCompensatesWhenEverythingIsBack() {
        when(subscriptions.affectedBy(anySet())).thenReturn(List.of(subscriber("Asha", "254700000001")));
        when(subscriptions.compensateForOutage(any(), any())).thenReturn(1);
        when(tickets.save(any(SupportTicket.class))).thenAnswer(call -> {
            SupportTicket t = call.getArgument(0);
            t.setId(99L);
            return t;
        });
        when(tickets.findById(99L)).thenAnswer(call ->
                Optional.of(SupportTicket.builder().id(99L).customerName("Network")
                        .phoneNumber("").subject("outage").build()));

        Incident incident = service.routerDown(westlands);
        service.routerDown(kasarani);
        incident.setStartedAt(Instant.now().minus(Duration.ofMinutes(45)));
        service.notifyRipeIncidents();

        westlands.setOnline(true);
        kasarani.setOnline(true);
        service.routerUp(westlands);

        assertThat(incident.getStatus()).isEqualTo(Incident.Status.RESOLVED);
        assertThat(incident.getEndedAt()).isNotNull();
        assertThat(incident.getCompensatedCount()).isEqualTo(1);
        assertThat(incident.getResolvedNotifiedAt()).isNotNull();
        // Scoped to the routers that were actually down, not everybody.
        verify(subscriptions).compensateForOutage(any(Duration.class), eq(Set.of(1L, 2L)));
        verify(smsService).trySend(eq("254700000001"), contains("is back"));
    }

    @Test
    @DisplayName("customers who were never warned are not sent an all-clear")
    void staysQuietOnRecoveryIfItWasNeverAnnounced() {
        when(subscriptions.affectedBy(anySet())).thenReturn(List.of(subscriber("Asha", "254700000001")));

        service.routerDown(westlands);
        westlands.setOnline(true);
        service.routerUp(westlands);

        Incident incident = stored.values().iterator().next();
        assertThat(incident.getStatus()).isEqualTo(Incident.Status.RESOLVED);
        assertThat(incident.getResolvedNotifiedAt()).isNull();
        verifyNoInteractions(smsService);
    }

    @Test
    @DisplayName("the public status page carries areas and times, never customers")
    void publicStatusHidesCustomerDetail() {
        Incident incident = service.routerDown(westlands);
        incident.setStartedAt(Instant.now().minus(Duration.ofMinutes(20)));

        Map<String, Object> status = service.publicStatus();

        assertThat(status.get("operational")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> current = (List<Map<String, Object>>) status.get("current");
        assertThat(current).hasSize(1);
        assertThat(current.get(0).get("area")).isEqualTo("Westlands");
        assertThat(current.get(0)).doesNotContainKeys("phone", "phoneNumber", "customers");
    }

    private Subscriber subscriber(String name, String phone) {
        return Subscriber.builder().fullName(name).phoneNumber(phone).build();
    }
}
