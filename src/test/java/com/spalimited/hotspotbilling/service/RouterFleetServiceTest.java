package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.RouterMove;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.RouterMoveRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Moving customers between routers.
 *
 * <p>Half-failure is the normal case here -- the new router takes twelve of
 * twenty and stops answering -- so most of these tests are about what happens to
 * the other eight. A batch that rolls back on the twelfth leaves the operator no
 * better off and the router holding secrets for customers the database says are
 * somewhere else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouterFleetServiceTest {

    @Mock
    private RouterRepository routers;

    @Mock
    private SubscriberRepository subscribers;

    @Mock
    private RouterMoveRepository moves;

    @Mock
    private MikrotikService mikrotikService;

    @Mock
    private SubscriberProvisioningService provisioning;

    @Mock
    private AuditService audit;

    @InjectMocks
    private RouterFleetService fleet;

    private Router main;
    private Router spare;
    private final List<Subscriber> all = new ArrayList<>();

    @BeforeEach
    void setUp() {
        all.clear();
        main = router(1L, "Main");
        spare = router(2L, "Spare");
        when(mikrotikService.manageable(any())).thenReturn(true);
        when(subscribers.findAll()).thenReturn(all);
        when(subscribers.save(any())).thenAnswer(i -> i.getArgument(0));
        when(subscribers.findById(anyLong())).thenAnswer(i ->
                all.stream().filter(s -> i.getArgument(0).equals(s.getId())).findFirst());
        when(moves.save(any())).thenAnswer(i -> {
            RouterMove m = i.getArgument(0);
            if (m.getId() == null) {
                m.setId(7L);
            }
            return m;
        });
    }

    private Router router(long id, String name) {
        Router r = new Router();
        r.setId(id);
        r.setName(name);
        r.setEnabled(true);
        when(routers.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private Subscriber sub(long id, String username, Long routerId) {
        Subscriber s = Subscriber.builder()
                .id(id).fullName("Customer " + id).pppoeUsername(username)
                .pppoePassword("pass" + id).bandwidth("10M/10M").routerId(routerId).build();
        all.add(s);
        return s;
    }

    // --- transfer ---

    @Test
    @DisplayName("a transfer provisions on the destination before removing from the source")
    void provisionBeforeRemove() {
        sub(1, "a", 1L);

        fleet.transfer(List.of(1L), 2L, "grace");

        // Reversed, a failure on the second call leaves the customer with no
        // secret anywhere while the database says they are fine.
        InOrder order = inOrder(provisioning);
        order.verify(provisioning).provision(any());
        order.verify(provisioning).remove(any());
    }

    @Test
    @DisplayName("the removal targets the router they came from, not the one they went to")
    void removalUsesTheOldRouter() {
        sub(1, "a", 1L);

        fleet.transfer(List.of(1L), 2L, "grace");

        ArgumentCaptor<Subscriber> removed = ArgumentCaptor.forClass(Subscriber.class);
        verify(provisioning).remove(removed.capture());
        // Passing the updated customer would delete the secret from the router
        // they were just moved onto.
        assertThat(removed.getValue().getRouterId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the database only says they moved once the destination accepted them")
    void databaseFollowsTheRouter() {
        Subscriber s = sub(1, "a", 1L);
        doThrow(new IllegalStateException("connection refused"))
                .when(provisioning).provision(any());

        RouterFleetService.Outcome out = fleet.transfer(List.of(1L), 2L, "grace");

        assertThat(out.moved()).isZero();
        assertThat(out.failed()).isEqualTo(1);
        // Still on the old router, which is where they still actually are.
        assertThat(s.getRouterId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("one failure does not roll back the customers that moved")
    void partialSuccessIsKept() {
        Subscriber ok = sub(1, "ok", 1L);
        Subscriber bad = sub(2, "bad", 1L);
        doThrow(new IllegalStateException("timed out"))
                .when(provisioning).provision(
                        org.mockito.ArgumentMatchers.argThat(s -> "bad".equals(s.getPppoeUsername())));

        RouterFleetService.Outcome out = fleet.transfer(List.of(1L, 2L), 2L, "grace");

        assertThat(out.moved()).isEqualTo(1);
        assertThat(out.failed()).isEqualTo(1);
        assertThat(ok.getRouterId()).isEqualTo(2L);
        assertThat(bad.getRouterId()).isEqualTo(1L);
        // Named, so the one that failed is a list to retry rather than a
        // discrepancy found weeks later.
        assertThat(out.problems().get(0)).contains("bad");
    }

    @Test
    @DisplayName("a customer already on the destination is neither moved nor counted as failed")
    void alreadyThereIsNotWork() {
        sub(1, "a", 2L);

        RouterFleetService.Outcome out = fleet.transfer(List.of(1L), 2L, "grace");

        assertThat(out.moved()).isZero();
        assertThat(out.failed()).isZero();
        verify(provisioning, never()).provision(any());
    }

    @Test
    @DisplayName("a destination that is switched off is refused before anybody is touched")
    void unmanageableDestinationRefused() {
        sub(1, "a", 1L);
        when(mikrotikService.manageable(spare)).thenReturn(false);

        assertThatThrownBy(() -> fleet.transfer(List.of(1L), 2L, "grace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("switched off");

        verify(provisioning, never()).provision(any());
    }

    @Test
    @DisplayName("a stale secret left on the old router does not fail the move")
    void staleSecretIsTolerated() {
        Subscriber s = sub(1, "a", 1L);
        doThrow(new IllegalStateException("old box is dead")).when(provisioning).remove(any());

        RouterFleetService.Outcome out = fleet.transfer(List.of(1L), 2L, "grace");

        // The customer is on the new router and working. Failing over the tidy-up
        // would leave them provisioned twice with the database pointing nowhere.
        assertThat(out.moved()).isEqualTo(1);
        assertThat(s.getRouterId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("the attempt is recorded with what failed")
    void attemptIsRecorded() {
        sub(1, "a", 1L);
        doThrow(new IllegalStateException("timed out")).when(provisioning).provision(any());

        fleet.transfer(List.of(1L), 2L, "grace");

        ArgumentCaptor<RouterMove> saved = ArgumentCaptor.forClass(RouterMove.class);
        verify(moves, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        RouterMove last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getKind()).isEqualTo(RouterMove.Kind.TRANSFER);
        assertThat(last.getFailedCount()).isEqualTo(1);
        assertThat(last.getDetail()).contains("timed out");
        assertThat(last.getFinishedAt()).isNotNull();
    }

    // --- replace ---

    @Test
    @DisplayName("replacing moves everybody and switches the old router off")
    void replaceMovesEverybody() {
        sub(1, "a", 1L);
        sub(2, "b", 1L);
        sub(3, "elsewhere", 9L);

        RouterFleetService.Outcome out = fleet.replace(1L, 2L, false, "grace");

        assertThat(out.moved()).isEqualTo(2);
        assertThat(all.get(0).getRouterId()).isEqualTo(2L);
        assertThat(all.get(1).getRouterId()).isEqualTo(2L);
        // Somebody else's customer is left alone.
        assertThat(all.get(2).getRouterId()).isEqualTo(9L);
        assertThat(main.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("a router with customers still on it is left switched ON")
    void partialReplaceLeavesTheOldRouterOn() {
        sub(1, "ok", 1L);
        sub(2, "bad", 1L);
        doThrow(new IllegalStateException("timed out"))
                .when(provisioning).provision(
                        org.mockito.ArgumentMatchers.argThat(s -> "bad".equals(s.getPppoeUsername())));

        RouterFleetService.Outcome out = fleet.replace(1L, 2L, false, "grace");

        assertThat(out.failed()).isEqualTo(1);
        // Disabling it would strand the customer still on it: every later call
        // would skip the router as unmanageable and nothing would say why.
        assertThat(main.isEnabled()).isTrue();
        assertThat(out.message()).contains("left switched ON");
    }

    @Test
    @DisplayName("the site's identity moves with it when asked")
    void copySettingsMovesThePlaceNotTheBox() {
        main.setBranchId(4L);
        main.setCapacityMbps(200);
        main.setLocation("Westlands");

        fleet.replace(1L, 2L, true, "grace");

        // Which branch it serves and what its uplink carries are properties of
        // the site, not of the hardware -- losing them means capacity planning
        // silently loses a site.
        assertThat(spare.getBranchId()).isEqualTo(4L);
        assertThat(spare.getCapacityMbps()).isEqualTo(200);
        assertThat(spare.getLocation()).isEqualTo("Westlands");
    }

    @Test
    @DisplayName("settings are left alone unless asked for")
    void copySettingsIsOptional() {
        main.setCapacityMbps(200);

        fleet.replace(1L, 2L, false, "grace");

        assertThat(spare.getCapacityMbps()).isNull();
    }

    @Test
    @DisplayName("replacing a router with itself is refused")
    void cannotReplaceWithItself() {
        assertThatThrownBy(() -> fleet.replace(1L, 1L, false, "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same router");
    }

    @Test
    @DisplayName("replacing an empty router still switches it off")
    void emptyRouterIsStillRetired() {
        RouterFleetService.Outcome out = fleet.replace(1L, 2L, false, "grace");

        assertThat(out.moved()).isZero();
        assertThat(out.failed()).isZero();
        assertThat(main.isEnabled()).isFalse();
    }
}
