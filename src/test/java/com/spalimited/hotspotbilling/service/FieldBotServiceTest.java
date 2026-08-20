package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.FieldSettings;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.FieldSettingsRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Field work run from a chat window. These are the paths that cannot be tried
 * without a technician, a handset and a job: being recognised by phone number
 * at all, claiming from the queue, closing a job so the customer hears about
 * it, and the sweep chasing quiet work once rather than every quarter hour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FieldBotServiceTest {

    private static final String TECH_PHONE = "254711000111";
    private static final String STRANGER = "254722333444";
    private static final String ALERT_PHONE = "254700999888";

    @Mock private FieldSettingsRepository settingsRepo;
    @Mock private TechnicianRepository technicians;
    @Mock private SupportTicketRepository tickets;
    @Mock private SmsService smsService;
    @Mock private OperatorAlertService operatorAlerts;
    @Mock private MessagingSettingsService messagingSettings;
    @Mock private PortalSettingsService portalSettings;

    private FieldOpsService fieldOps;
    private FieldBotService bot;
    private FieldChatPin fieldChatPin;

    /** Ann's PIN throughout. Varied digits, because all-same is refused. */
    private static final String PIN = "2468";

    private final Map<Long, SupportTicket> stored = new LinkedHashMap<>();
    private Technician ann;
    private FieldSettings settings;

    @BeforeEach
    void setUp() {
        fieldOps = new FieldOpsService(settingsRepo, technicians, tickets, smsService,
                operatorAlerts, messagingSettings, portalSettings);
        fieldChatPin = new FieldChatPin(technicians,
                org.springframework.security.crypto.factory.PasswordEncoderFactories
                        .createDelegatingPasswordEncoder(),
                operatorAlerts);
        bot = new FieldBotService(fieldOps, tickets, fieldChatPin);

        settings = FieldSettings.builder().id(FieldSettings.SINGLETON_ID).build();
        when(settingsRepo.findById(FieldSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        when(settingsRepo.save(any(FieldSettings.class))).thenAnswer(i -> i.getArgument(0));

        // Stored with a leading zero on purpose: the office types numbers the
        // way people say them, and WhatsApp reports them in full international
        // form, so the two only ever meet if both are normalised.
        ann = Technician.builder().id(7L).username("ann").fullName("Ann Wanjiru")
                .phoneNumber("0711000111").active(true).createdAt(Instant.now()).build();
        when(technicians.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(ann));
        when(technicians.findById(7L)).thenReturn(Optional.of(ann));

        when(messagingSettings.alertPhone()).thenReturn(ALERT_PHONE);
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().businessName("SPA WiFi").build());

        when(tickets.findById(any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(tickets.save(any(SupportTicket.class))).thenAnswer(i -> {
            SupportTicket t = i.getArgument(0);
            stored.put(t.getId(), t);
            return t;
        });
        when(tickets.findByStatusInOrderByCreatedAtAsc(any())).thenAnswer(i -> {
            List<SupportTicket.Status> want = new ArrayList<>(i.getArgument(0));
            return stored.values().stream().filter(t -> want.contains(t.getStatus())).toList();
        });

        // Ann signs in once, so the tests below exercise the bot rather than the
        // lock on the front of it. The lock has its own tests at the bottom.
        fieldChatPin.setPin(7L, PIN, "test");
        bot.reply(TECH_PHONE, "hi");
        bot.reply(TECH_PHONE, PIN);
    }

    /** A bot with its own session map, for testing the lock from a cold start. */
    private FieldBotService coldBot() {
        return new FieldBotService(fieldOps, tickets, fieldChatPin);
    }

    private SupportTicket job(long id, SupportTicket.Status status, Instant created, Long... assignees) {
        SupportTicket t = SupportTicket.builder()
                .id(id)
                .customerName("Jane Doe")
                .phoneNumber("254733111222")
                .subject("No internet since morning")
                .priority(SupportTicket.Priority.HIGH)
                .status(status)
                .createdAt(created)
                .updatedAt(created)
                .build();
        for (Long a : assignees) {
            t.getAssigneeIds().add(a);
        }
        if (assignees.length > 0) {
            t.setWorkStartedAt(created);
        }
        stored.put(id, t);
        return t;
    }

    @Test
    @DisplayName("Only a technician's own number opens the field menu")
    void recognisesTechnicianByPhone() {
        assertThat(bot.reply(TECH_PHONE, "jobs")).contains("Field Connect", "Ann");
        // A customer must fall through to the customer bot, not be told
        // anything about the job queue.
        assertThat(bot.reply(STRANGER, "jobs")).isNull();
    }

    @Test
    @DisplayName("A disabled technician is no longer recognised")
    void ignoresDisabledTechnician() {
        ann.setActive(false);
        assertThat(bot.reply(TECH_PHONE, "jobs")).isNull();
    }

    @Test
    @DisplayName("Turning the assistant off hands technicians back to the customer bot")
    void respectsTheOffSwitch() {
        settings.setWhatsappEnabled(false);
        assertThat(bot.reply(TECH_PHONE, "jobs")).isNull();
    }

    @Test
    @DisplayName("Taking a job from the queue assigns it and starts the clock")
    void claimsFromQueue() {
        job(42L, SupportTicket.Status.OPEN, Instant.now().minus(Duration.ofMinutes(20)));

        bot.reply(TECH_PHONE, "jobs");
        assertThat(bot.reply(TECH_PHONE, "2")).contains("Waiting for someone", "#42");
        String card = bot.reply(TECH_PHONE, "1");

        assertThat(card).contains("Job #42 is yours");
        SupportTicket claimed = stored.get(42L);
        assertThat(claimed.getAssigneeIds()).containsExactly(7L);
        assertThat(claimed.getStatus()).isEqualTo(SupportTicket.Status.IN_PROGRESS);
        assertThat(claimed.getWorkStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("A job someone else grabbed first is refused rather than double-assigned")
    void refusesAJobAlreadyTaken() {
        SupportTicket t = job(42L, SupportTicket.Status.OPEN, Instant.now().minus(Duration.ofMinutes(20)));
        bot.reply(TECH_PHONE, "jobs");
        bot.reply(TECH_PHONE, "2");
        // Between the list going out and the reply coming back, someone else took it.
        t.getAssigneeIds().add(9L);

        assertThat(bot.reply(TECH_PHONE, "1")).contains("already picked up");
        assertThat(stored.get(42L).getAssigneeIds()).containsExactly(9L);
    }

    @Test
    @DisplayName("Closing a job records who closed it and tells the customer what was done")
    void closesAJobAndTellsTheCustomer() {
        job(42L, SupportTicket.Status.IN_PROGRESS, Instant.now().minus(Duration.ofHours(2)), 7L);

        bot.reply(TECH_PHONE, "jobs");
        bot.reply(TECH_PHONE, "1");
        bot.reply(TECH_PHONE, "1");
        bot.reply(TECH_PHONE, "3");
        String done = bot.reply(TECH_PHONE, "Replaced the power adapter");

        assertThat(done).contains("closed");
        SupportTicket closed = stored.get(42L);
        assertThat(closed.getStatus()).isEqualTo(SupportTicket.Status.RESOLVED);
        assertThat(closed.getResolvedBy()).isEqualTo("Ann Wanjiru");
        assertThat(closed.getResolvedAt()).isNotNull();
        assertThat(closed.getMessages()).extracting("body")
                .anyMatch(b -> String.valueOf(b).contains("Replaced the power adapter"));
        verify(smsService).trySend(eq("254733111222"),
                org.mockito.ArgumentMatchers.contains("Replaced the power adapter"));
    }

    @Test
    @DisplayName("Closing without a note still closes, and says nothing it cannot back up")
    void closesWithoutANote() {
        job(42L, SupportTicket.Status.IN_PROGRESS, Instant.now().minus(Duration.ofHours(2)), 7L);

        bot.reply(TECH_PHONE, "jobs");
        bot.reply(TECH_PHONE, "1");
        bot.reply(TECH_PHONE, "1");
        bot.reply(TECH_PHONE, "3");
        bot.reply(TECH_PHONE, "skip");

        assertThat(stored.get(42L).getStatus()).isEqualTo(SupportTicket.Status.RESOLVED);
        verify(smsService).trySend(eq("254733111222"),
                org.mockito.ArgumentMatchers.contains("has been resolved"));
    }

    @Test
    @DisplayName("A quiet job is chased once, not on every sweep")
    void nudgesAStaleJobOnce() {
        job(42L, SupportTicket.Status.IN_PROGRESS,
                Instant.now().minus(Duration.ofHours(9)), 7L);

        assertThat(fieldOps.runSweep()).containsEntry("nudged", 1);
        assertThat(fieldOps.runSweep()).containsEntry("nudged", 0);
        verify(smsService, times(1)).trySend(eq(TECH_PHONE),
                org.mockito.ArgumentMatchers.contains("no update"));
    }

    @Test
    @DisplayName("A note counts as progress, so the chase starts over rather than repeating")
    void aNoteResetsTheChase() {
        job(42L, SupportTicket.Status.IN_PROGRESS, Instant.now().minus(Duration.ofHours(9)), 7L);
        fieldOps.runSweep();

        bot.reply(TECH_PHONE, "jobs");
        bot.reply(TECH_PHONE, "1");
        bot.reply(TECH_PHONE, "1");
        bot.reply(TECH_PHONE, "1");
        bot.reply(TECH_PHONE, "Waiting for the landlord to open the room");

        assertThat(stored.get(42L).getLastNudgedAt()).isNull();
        assertThat(fieldOps.runSweep()).containsEntry("nudged", 0);
    }

    @Test
    @DisplayName("A job nobody has taken is escalated to the operator, once")
    void escalatesAnUnclaimedJob() {
        job(42L, SupportTicket.Status.OPEN, Instant.now().minus(Duration.ofHours(3)));

        assertThat(fieldOps.runSweep()).containsEntry("escalated", 1);
        assertThat(fieldOps.runSweep()).containsEntry("escalated", 0);
        // Once, through the operator alert channel -- the escalation is what is
        // being tested, not the delivery.
        verify(operatorAlerts, times(1))
                .alert(org.mockito.ArgumentMatchers.contains("nobody on it"));
    }

    @Test
    @DisplayName("A job well inside its window is left alone")
    void leavesFreshWorkAlone() {
        job(42L, SupportTicket.Status.IN_PROGRESS, Instant.now().minus(Duration.ofMinutes(30)), 7L);
        job(43L, SupportTicket.Status.OPEN, Instant.now().minus(Duration.ofMinutes(5)));

        Map<String, Object> result = fieldOps.runSweep();

        assertThat(result).containsEntry("nudged", 0).containsEntry("escalated", 0);
        verify(smsService, never()).trySend(anyString(), anyString());
    }

    // ------------------------------------------------------------ the lock

    @Test
    @DisplayName("a cold chat is asked for a PIN and told nothing else")
    void coldChatAsksForThePin() {
        job(42, SupportTicket.Status.OPEN, Instant.now());
        FieldBotService cold = coldBot();

        String first = cold.reply(TECH_PHONE, "jobs");

        // Not the name, not a job count. Either would tell whoever is holding
        // the phone that this number is a technician's and how much work is on.
        assertThat(first).contains("Enter your field PIN");
        assertThat(first).doesNotContain("Ann");
        assertThat(first).doesNotContain("42");
    }

    @Test
    @DisplayName("the right PIN opens the menu")
    void rightPinOpensTheMenu() {
        FieldBotService cold = coldBot();
        cold.reply(TECH_PHONE, "jobs");

        assertThat(cold.reply(TECH_PHONE, PIN)).contains("Field Connect", "Ann");
    }

    @Test
    @DisplayName("a wrong PIN says how many tries are left and shows nothing")
    void wrongPinCountsDown() {
        FieldBotService cold = coldBot();
        cold.reply(TECH_PHONE, "jobs");

        String reply = cold.reply(TECH_PHONE, "9999");

        assertThat(reply).contains("not right", "tries left");
        assertThat(reply).doesNotContain("Ann");
    }

    @Test
    @DisplayName("five wrong PINs lock the chat and tell the operator")
    void fiveWrongPinsLock() {
        FieldBotService cold = coldBot();
        cold.reply(TECH_PHONE, "jobs");
        for (int i = 0; i < 4; i++) {
            cold.reply(TECH_PHONE, "9999");
        }

        assertThat(cold.reply(TECH_PHONE, "9999")).contains("locked");
        // The operator is the only one who can tell a forgotten PIN from a
        // stolen phone, so they are told either way.
        verify(operatorAlerts).alert(org.mockito.ArgumentMatchers.contains("locked"));
        // And the right PIN does not work while it is locked.
        assertThat(cold.reply(TECH_PHONE, PIN)).contains("locked");
    }

    @Test
    @DisplayName("the guess budget survives saying menu between tries")
    void budgetSurvivesAReset() {
        FieldBotService cold = coldBot();
        cold.reply(TECH_PHONE, "jobs");
        for (int i = 0; i < 4; i++) {
            cold.reply(TECH_PHONE, "9999");
            // The obvious way to get a fresh budget, if the count lived in the
            // chat session rather than on the technician.
            cold.reply(TECH_PHONE, "menu");
        }

        assertThat(cold.reply(TECH_PHONE, "9999")).contains("locked");
    }

    @Test
    @DisplayName("a technician with no PIN set cannot use the chat at all")
    void noPinNoChat() {
        fieldChatPin.clearPin(7L, "test");
        FieldBotService cold = coldBot();

        String reply = cold.reply(TECH_PHONE, "jobs");

        // Fail closed, and say who can fix it. Letting them set their own here
        // would mean whoever holds the phone sets it first.
        assertThat(reply).contains("not set up", "office");
        assertThat(reply).doesNotContain("Ann");
    }

    @Test
    @DisplayName("a stranger still gets nothing, PIN or no PIN")
    void strangerStillGetsNothing() {
        assertThat(coldBot().reply(STRANGER, "2468")).isNull();
    }

    @Test
    @DisplayName("an easily guessed PIN is refused when it is set")
    void weakPinRefused() {
        assertThatThrownBy(() -> fieldChatPin.setPin(7L, "0000", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too easy");
        assertThatThrownBy(() -> fieldChatPin.setPin(7L, "12", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digits");
    }
}
