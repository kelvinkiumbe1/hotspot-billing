package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Editing a customer.
 *
 * <p>Until now there was no way to: subscribers could be created and deleted and
 * nothing in between, so fixing a misspelled name meant deleting the customer and
 * starting again, which threw away their payment history and their invoices.
 *
 * <p>The tests that matter are about the router. A rename is two operations and
 * the order decides whether a failure leaves a harmless duplicate or a customer
 * with no way to connect at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriberEditTest {

    @Mock
    private SubscriberRepository subscribers;

    @Mock
    private MikrotikService mikrotikService;

    @Mock
    private AuditService audit;

    @InjectMocks
    private SubscriptionService service;

    private Subscriber mary;

    @BeforeEach
    void setUp() {
        mary = Subscriber.builder()
                .id(7L).fullName("Mary Kamau").phoneNumber("254712345678")
                .pppoeUsername("mkamau").pppoePassword("secret12")
                .bandwidth("10M/10M").monthlyFee(new BigDecimal("2500"))
                .routerId(3L).status(Subscriber.Status.ACTIVE)
                .paidUntil(Instant.now().plusSeconds(86400))
                .build();
        when(subscribers.findById(7L)).thenReturn(Optional.of(mary));
        when(subscribers.save(any())).thenAnswer(i -> i.getArgument(0));
        when(subscribers.findByPppoeUsername(anyString())).thenReturn(Optional.empty());
    }

    private SubscriptionService.Edited edit(String name, String username, String bandwidth,
                                            BigDecimal fee, Long routerId) {
        return service.update(7L, name, null, username, null, bandwidth, fee, routerId,
                null, null, "grace");
    }

    @Test
    @DisplayName("changing only the name does not touch the router at all")
    void nameOnlyIsLocal() {
        SubscriptionService.Edited result = edit("Mary Wanjiku", null, null, null, null);

        assertThat(result.changes()).containsExactly("name");
        assertThat(mary.getFullName()).isEqualTo("Mary Wanjiku");
        // Reprovisioning for a spelling correction would drop a live customer
        // for no reason.
        verify(mikrotikService, never()).provisionPppoe(any());
    }

    @Test
    @DisplayName("changing the monthly fee does not touch the router either")
    void feeIsLocal() {
        SubscriptionService.Edited result = edit(null, null, null, new BigDecimal("3000"), null);

        assertThat(result.changes()).containsExactly("monthly fee");
        assertThat(mary.getMonthlyFee()).isEqualByComparingTo("3000");
        verify(mikrotikService, never()).provisionPppoe(any());
    }

    @Test
    @DisplayName("an edit that changes nothing says so and touches nothing")
    void noChange() {
        SubscriptionService.Edited result = edit("Mary Kamau", "mkamau", "10M/10M",
                new BigDecimal("2500"), 3L);

        assertThat(result.changes()).isEmpty();
        assertThat(result.note()).isEqualTo("Nothing was different.");
        verify(subscribers, never()).save(any());
        verify(mikrotikService, never()).provisionPppoe(any());
    }

    @Test
    @DisplayName("a rename creates the new secret BEFORE removing the old one")
    void renameCreatesBeforeRemoving() {
        SubscriptionService.Edited result = edit(null, "mwanjiku", null, null, null);

        assertThat(result.changes()).containsExactly("PPPoE username");
        // The whole point. Remove-then-create leaves the customer with no secret
        // at all if the create fails; this way the worst case is a duplicate.
        InOrder order = inOrder(mikrotikService);
        order.verify(mikrotikService).provisionPppoe(any());
        order.verify(mikrotikService).removePppoe(any());
    }

    @Test
    @DisplayName("the old secret is removed under the OLD username, not the new one")
    void removalUsesTheOldIdentity() {
        edit(null, "mwanjiku", null, null, null);

        org.mockito.ArgumentCaptor<Subscriber> removed =
                org.mockito.ArgumentCaptor.forClass(Subscriber.class);
        verify(mikrotikService).removePppoe(removed.capture());
        // Removing under the new name would delete the secret we just created
        // and leave the customer offline with a database that says otherwise.
        assertThat(removed.getValue().getPppoeUsername()).isEqualTo("mkamau");
    }

    @Test
    @DisplayName("a rename says the customer stays offline until their own router is changed")
    void renameWarnsAboutTheCustomerRouter() {
        SubscriptionService.Edited result = edit(null, "mwanjiku", null, null, null);

        // Somebody has to drive to the house or talk them through it. Not saying
        // so turns into a support call that nobody can explain.
        assertThat(result.note()).contains("offline until");
        assertThat(result.note()).contains("customer");
    }

    @Test
    @DisplayName("a username already taken by somebody else is refused")
    void usernameCollisionIsRefused() {
        Subscriber other = Subscriber.builder().id(9L).pppoeUsername("taken").build();
        when(subscribers.findByPppoeUsername("taken")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> edit(null, "taken", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already taken");

        assertThat(mary.getPppoeUsername()).isEqualTo("mkamau");
        verify(mikrotikService, never()).provisionPppoe(any());
    }

    @Test
    @DisplayName("keeping your own username is not a collision with yourself")
    void ownUsernameIsNotACollision() {
        when(subscribers.findByPppoeUsername("mkamau")).thenReturn(Optional.of(mary));

        SubscriptionService.Edited result = service.update(7L, "Mary W", null, "mkamau",
                null, null, null, null, null, null, "grace");

        assertThat(result.changes()).containsExactly("name");
    }

    @Test
    @DisplayName("a speed change warns that it lands on the next reconnect")
    void speedChangeWarnsAboutReconnect() {
        SubscriptionService.Edited result = edit(null, null, "20M/20M", null, null);

        assertThat(result.changes()).containsExactly("speed");
        assertThat(result.reconnectNeeded()).isTrue();
        // RouterOS applies a profile at dial-in. Reporting this as done would be
        // a lie the operator repeats to the customer.
        assertThat(result.note()).contains("reconnects");
        verify(mikrotikService).provisionPppoe(any());
        verify(mikrotikService, never()).removePppoe(any());
    }

    @Test
    @DisplayName("giving a previously uncapped customer a speed needs no reconnect warning")
    void firstSpeedNeedsNoWarning() {
        mary.setBandwidth(null);

        SubscriptionService.Edited result = edit(null, null, "20M/20M", null, null);

        // There was no old rate to still be in force, so nothing is stale.
        assertThat(result.reconnectNeeded()).isFalse();
        assertThat(result.note()).doesNotContain("reconnects");
    }

    @Test
    @DisplayName("a blank speed takes the limit off rather than being ignored")
    void blankBandwidthClearsIt() {
        SubscriptionService.Edited result = edit(null, null, "", null, null);

        assertThat(result.changes()).containsExactly("speed");
        assertThat(mary.getBandwidth()).isNull();
    }

    @Test
    @DisplayName("moving a customer to another router provisions there and cleans up here")
    void movingRouters() {
        SubscriptionService.Edited result = edit(null, null, null, null, 4L);

        assertThat(result.changes()).containsExactly("router");
        assertThat(mary.getRouterId()).isEqualTo(4L);

        org.mockito.ArgumentCaptor<Subscriber> removed =
                org.mockito.ArgumentCaptor.forClass(Subscriber.class);
        verify(mikrotikService).removePppoe(removed.capture());
        // Cleaned off the router they came FROM. Passing the updated subscriber
        // would delete the secret from the router they just moved to.
        assertThat(removed.getValue().getRouterId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("a router that refuses the change fails the edit rather than lying about it")
    void routerFailureFailsTheEdit() {
        doThrow(new IllegalStateException("MikroTik API call failed: connection refused"))
                .when(mikrotikService).provisionPppoe(any());

        assertThatThrownBy(() -> edit(null, null, "20M/20M", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");

        // The transaction rolls back, so the database never claims a speed the
        // router does not have. Nothing was removed either.
        verify(mikrotikService, never()).removePppoe(any());
    }

    @Test
    @DisplayName("a failed cleanup does not undo an edit that already worked")
    void failedCleanupIsToleratedAndLogged() {
        doThrow(new IllegalStateException("connection refused"))
                .when(mikrotikService).removePppoe(any());

        SubscriptionService.Edited result = edit(null, "mwanjiku", null, null, null);

        // The new secret exists and the rename stands. A stale secret nobody
        // uses is untidy; throwing away a completed change is worse.
        assertThat(result.changes()).containsExactly("PPPoE username");
        assertThat(mary.getPppoeUsername()).isEqualTo("mwanjiku");
    }

    @Test
    @DisplayName("several fields at once are all reported")
    void multipleChanges() {
        SubscriptionService.Edited result = service.update(7L, "Mary Wanjiku", "254700111222",
                null, null, "20M/20M", new BigDecimal("3200"), null, 2L, "10.20.0.5", "grace");

        assertThat(result.changes()).containsExactlyInAnyOrder(
                "name", "phone number", "speed", "monthly fee", "branch", "static address");
        assertThat(mary.getStaticIp()).isEqualTo("10.20.0.5");
        assertThat(mary.getBranchId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("the edit is audited with who did it and what changed")
    void editIsAudited() {
        edit("Mary Wanjiku", null, null, null, null);

        verify(audit).record(org.mockito.ArgumentMatchers.eq("grace"),
                org.mockito.ArgumentMatchers.eq("subscriber.update"),
                org.mockito.ArgumentMatchers.contains("name"));
    }
}
