package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.NotificationTemplate;
import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fair use for monthly customers.
 *
 * <p>The applying half is the easy half. What these tests are really about is
 * the undoing: a fibre customer throttled in June and still throttled in July is
 * somebody paying full price for a quarter of their speed, and nothing in the
 * admin shows it unless a person goes looking. So most of what follows is about
 * getting a customer's speed back to them -- when the month rolls over, when the
 * cap is deleted, and when the router was unreachable the first time we tried.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriberFupTest {

    private static final long MB = 1024L * 1024L;

    @Mock
    private MikrotikService mikrotikService;

    @Mock
    private SubscriberProvisioningService provisioning;

    @Mock
    private NotificationService notificationService;

    @Mock
    private PortalSettingsService portalSettingsService;

    @Mock
    private VoucherRepository vouchers;

    @Mock
    private SubscriberRepository subscribers;

    @Mock
    private SubscriberUsageService subscriberUsage;

    @InjectMocks
    private FupService fup;

    private Subscriber sub;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.of(2026, 7, 14);
        when(subscriberUsage.today()).thenReturn(today);
        when(subscriberUsage.cycleStart(any())).thenAnswer(i ->
                ((LocalDate) i.getArgument(0)).withDayOfMonth(1));
        when(subscribers.save(any())).thenAnswer(i -> i.getArgument(0));

        PortalSettings settings = new PortalSettings();
        settings.setBusinessName("SPA WiFi");
        when(portalSettingsService.settings()).thenReturn(settings);

        sub = Subscriber.builder()
                .id(7L).fullName("Achieng").phoneNumber("254700000001")
                .pppoeUsername("achieng").pppoePassword("secret")
                .bandwidth("20M/20M")
                .dataCapMb(50_000).fupAction(Plan.FupAction.THROTTLE).fupRate("2M/2M")
                .build();
    }

    private void used(long mb) {
        when(subscriberUsage.thisCycleBytes(7L)).thenReturn(mb * MB);
    }

    @Test
    @DisplayName("under the cap, nothing is touched")
    void underCap() {
        used(49_999);

        assertThat(fup.reviewSubscriber(sub)).isFalse();

        verify(provisioning, never()).setRate(any(), any());
        assertThat(sub.getFupAppliedAt()).isNull();
    }

    @Test
    @DisplayName("over the cap, the customer is throttled and the month is recorded")
    void throttlesAtCap() {
        used(50_000);

        assertThat(fup.reviewSubscriber(sub)).isTrue();

        verify(provisioning).setRate(sub, "2M/2M");
        assertThat(sub.getFupAppliedAt()).isNotNull();
        // The cycle, not the date. This is what makes the rollover check work.
        assertThat(sub.getFupCycle()).isEqualTo(LocalDate.of(2026, 7, 1));
        verify(notificationService).send(eq(NotificationTemplate.Key.FUP_NOTICE),
                eq("254700000001"), anyMap());
    }

    @Test
    @DisplayName("a customer already throttled this month is not throttled again")
    void appliesOncePerCycle() {
        used(80_000);
        sub.setFupAppliedAt(Instant.now());
        sub.setFupCycle(LocalDate.of(2026, 7, 1));

        assertThat(fup.reviewSubscriber(sub)).isFalse();

        verify(provisioning, never()).setRate(any(), any());
        // And crucially no second SMS -- one a month, not one every ten minutes.
        verify(notificationService, never()).send(any(), any(), anyMap());
    }

    @Test
    @DisplayName("the new month gives the speed back, even to a customer still over the old cap")
    void liftsOnRollover() {
        // Still 80GB of usage on the books, but that was last month's.
        used(80_000);
        sub.setFupAppliedAt(Instant.now());
        sub.setFupCycle(LocalDate.of(2026, 6, 1));

        assertThat(fup.reviewSubscriber(sub)).isTrue();

        // Null restores the subscriber's own bandwidth rather than a remembered
        // one, so a package change while throttled cannot hand back the wrong speed.
        verify(provisioning).setRate(sub, null);
        assertThat(sub.getFupAppliedAt()).isNull();
        assertThat(sub.getFupCycle()).isNull();
    }

    @Test
    @DisplayName("deleting the allowance releases a throttled customer")
    void liftsWhenCapRemoved() {
        sub.setFupAppliedAt(Instant.now());
        sub.setFupCycle(LocalDate.of(2026, 7, 1));
        sub.setDataCapMb(null);

        assertThat(fup.reviewSubscriber(sub)).isTrue();

        verify(provisioning).setRate(sub, null);
        assertThat(sub.getFupAppliedAt()).isNull();
    }

    @Test
    @DisplayName("an uncapped customer who was never throttled is left alone")
    void uncappedIsNotWork() {
        sub.setDataCapMb(null);

        assertThat(fup.reviewSubscriber(sub)).isFalse();

        verify(provisioning, never()).setRate(any(), any());
    }

    @Test
    @DisplayName("an unreachable router leaves the customer unmarked so the next sweep retries")
    void routerDownDoesNotMarkApplied() {
        used(60_000);
        doThrow(new IllegalStateException("connection refused"))
                .when(provisioning).setRate(any(), any());

        assertThat(fup.reviewSubscriber(sub)).isFalse();

        // The mark is what stops a retry. Setting it after a failed throttle
        // would leave a customer over their cap at full speed forever, with the
        // record claiming they had been throttled.
        assertThat(sub.getFupAppliedAt()).isNull();
        verify(notificationService, never()).send(any(), any(), anyMap());
    }

    @Test
    @DisplayName("an unreachable router at rollover keeps the mark so the lift is retried")
    void routerDownDoesNotClearMark() {
        used(10);
        sub.setFupAppliedAt(Instant.now());
        sub.setFupCycle(LocalDate.of(2026, 6, 1));
        doThrow(new IllegalStateException("connection refused"))
                .when(provisioning).setRate(any(), isNull());

        assertThat(fup.reviewSubscriber(sub)).isFalse();

        // Clearing it here would mean the sweep never tries again and the
        // customer stays throttled for the rest of the month.
        assertThat(sub.getFupAppliedAt()).isNotNull();
        assertThat(sub.getFupCycle()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    @DisplayName("BLOCK disables the line, and the rollover re-enables it")
    void blockAndRestore() {
        sub.setFupAction(Plan.FupAction.BLOCK);
        used(50_000);

        assertThat(fup.reviewSubscriber(sub)).isTrue();
        verify(provisioning).setEnabled(sub, false);

        sub.setFupCycle(LocalDate.of(2026, 6, 1));
        assertThat(fup.reviewSubscriber(sub)).isTrue();
        verify(provisioning).setEnabled(sub, true);
    }

    @Test
    @DisplayName("NOTIFY messages the customer and never touches the router")
    void notifyOnly() {
        sub.setFupAction(Plan.FupAction.NOTIFY);
        used(50_000);

        assertThat(fup.reviewSubscriber(sub)).isTrue();

        verify(notificationService).send(eq(NotificationTemplate.Key.FUP_NOTICE),
                eq("254700000001"), anyMap());
        verify(provisioning, never()).setRate(any(), any());
        verify(provisioning, never()).setEnabled(any(), anyBoolean());
    }

    @Test
    @DisplayName("THROTTLE with no rate set behaves as notify rather than as nothing")
    void throttleWithoutRate() {
        sub.setFupRate(null);
        used(50_000);

        assertThat(fup.reviewSubscriber(sub)).isTrue();

        verify(provisioning, never()).setRate(any(), any());
        // Still marked and still messaged: a half-configured cap that silently
        // did nothing at all would be worse than one that tells the customer.
        assertThat(sub.getFupAppliedAt()).isNotNull();
        verify(notificationService).send(any(), any(), anyMap());
    }
}
