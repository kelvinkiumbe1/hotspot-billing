package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CallAgent;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.CallRecordRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import com.spalimited.hotspotbilling.service.calls.CallCentreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A technician ringing a customer from the business number.
 *
 * <p>The field app used to offer a {@code tel:} link, which dials from the
 * technician's own handset and shows the customer their personal number. Fixing
 * that means giving a technician the power to make the business call somebody,
 * so most of these tests are about the limits on that power rather than the
 * happy path: only their own jobs, only the number on the job, and never a seat
 * on the inbound rota.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TechCallControllerTest {

    private static final long ANN = 7L;
    private static final long SOMEBODY_ELSE = 8L;

    @Mock private CallCentreService callCentre;
    @Mock private TechnicianRepository technicians;
    @Mock private SupportTicketRepository tickets;
    @Mock private CallRecordRepository calls;

    @InjectMocks
    private TechCallController controller;

    private final Principal ann = () -> "ann";
    private Technician technician;

    @BeforeEach
    void setUp() {
        technician = Technician.builder().id(ANN).username("ann").fullName("Ann Wanjiru")
                .phoneNumber("0711000111").active(true).createdAt(Instant.now()).build();
        when(technicians.findByUsername("ann")).thenReturn(Optional.of(technician));
        when(callCentre.whyNotUsable()).thenReturn(null);
        when(callCentre.agentForTechnician(any())).thenReturn(
                CallAgent.builder().id(99L).technicianId(ANN).name("Ann Wanjiru")
                        .phoneNumber("254711000111").inbound(false).active(true).build());
        when(callCentre.dial(anyLong(), anyString(), any(), any()))
                .thenReturn(new CallCentreService.Dialled(true, "session-1", "ringing"));
        when(calls.findTop50ByAgentIdOrderByStartedAtDesc(anyLong())).thenReturn(List.of());
    }

    private SupportTicket job(long id, String phone, Long... assignees) {
        Set<Long> ids = new LinkedHashSet<>(List.of(assignees));
        SupportTicket t = SupportTicket.builder()
                .id(id).subject("No internet").phoneNumber(phone)
                .status(SupportTicket.Status.OPEN).assigneeIds(ids)
                .createdAt(Instant.now()).build();
        when(tickets.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    // --- the limits ---

    @Test
    @DisplayName("a technician can ring the customer on their own job")
    void ownJobIsAllowed() {
        job(42, "0722000333", ANN);

        Map<String, Object> out = controller.dial(new TechCallController.DialRequest(42L), ann);

        assertThat(out.get("ok")).isEqualTo(true);
        verify(callCentre).dial(eq(99L), eq("0722000333"), isNull(), eq(42L));
    }

    @Test
    @DisplayName("somebody else's job is refused, and nothing is dialled")
    void otherPeoplesJobsAreRefused() {
        job(42, "0722000333", SOMEBODY_ELSE);

        assertThatThrownBy(() -> controller.dial(new TechCallController.DialRequest(42L), ann))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not assigned to you");

        // Without this the field app is a directory of every customer's number
        // for anybody holding a technician login.
        verify(callCentre, never()).dial(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an unclaimed job is refused too — assigned means assigned")
    void unclaimedJobIsRefused() {
        job(42, "0722000333");

        assertThatThrownBy(() -> controller.dial(new TechCallController.DialRequest(42L), ann))
                .isInstanceOf(IllegalArgumentException.class);
        verify(callCentre, never()).dial(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the number comes from the job, so a tampered request cannot redirect the call")
    void numberComesFromTheRecord() {
        job(42, "0722000333", ANN);

        controller.dial(new TechCallController.DialRequest(42L), ann);

        // The request carries a ticket id and nothing else: there is no field an
        // attacker could put a number in.
        verify(callCentre).dial(anyLong(), eq("0722000333"), any(), any());
    }

    @Test
    @DisplayName("a job with no phone number on it says so rather than dialling nothing")
    void jobWithoutANumber() {
        job(42, null, ANN);

        assertThatThrownBy(() -> controller.dial(new TechCallController.DialRequest(42L), ann))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no phone number");
    }

    @Test
    @DisplayName("a login that is not an active technician cannot call at all")
    void inactiveTechnicianRefused() {
        technician.setActive(false);
        job(42, "0722000333", ANN);

        assertThatThrownBy(() -> controller.dial(new TechCallController.DialRequest(42L), ann))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an active technician");
        verify(callCentre, never()).dial(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a job that does not exist is a clear refusal, not a crash")
    void unknownJob() {
        when(tickets.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.dial(new TechCallController.DialRequest(999L), ann))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such job");
    }

    // --- what the app is told before it draws the button ---

    @Test
    @DisplayName("a configured call centre and a known number means the button is live")
    void statusAvailable() {
        Map<String, Object> out = controller.status(ann);

        assertThat(out.get("available")).isEqualTo(true);
        assertThat(out.get("reason")).isNull();
    }

    @Test
    @DisplayName("an unconfigured call centre says so, so the app can fall back")
    void statusUnconfigured() {
        when(callCentre.whyNotUsable()).thenReturn("Calling is switched off.");

        Map<String, Object> out = controller.status(ann);

        // A button that fails when pressed on somebody's roof is worse than no
        // button.
        assertThat(out.get("available")).isEqualTo(false);
        assertThat(out.get("reason")).isEqualTo("Calling is switched off.");
    }

    @Test
    @DisplayName("a technician with no number on file is told what is missing")
    void statusWithoutAPhone() {
        technician.setPhoneNumber(null);

        Map<String, Object> out = controller.status(ann);

        assertThat(out.get("available")).isEqualTo(false);
        assertThat((String) out.get("reason")).contains("not on file");
    }

    // --- their own call list ---

    @Test
    @DisplayName("a technician sees their own calls and never a recording link")
    void ownCallsCarryNoRecording() {
        when(calls.findTop50ByAgentIdOrderByStartedAtDesc(99L)).thenReturn(List.of(
                com.spalimited.hotspotbilling.domain.CallRecord.builder()
                        .id(5L).sessionId("s1")
                        .direction(com.spalimited.hotspotbilling.domain.CallRecord.Direction.OUTBOUND)
                        .destinationNumber("254722000333").agentId(99L).ticketId(42L)
                        .status(com.spalimited.hotspotbilling.domain.CallRecord.Status.COMPLETED)
                        .durationSeconds(95).startedAt(Instant.now())
                        .recordingUrl("https://example.test/rec/5.mp3")
                        .createdAt(Instant.now()).build()));

        Map<String, Object> row = controller.mine(ann).get(0);

        assertThat(row.get("number")).isEqualTo("254722000333");
        assertThat(row.get("durationSeconds")).isEqualTo(95);
        // Recordings stay in the admin. A technician hearing their own call back
        // is defensible; the same endpoint one query away from the office's
        // recordings is not.
        assertThat(row).doesNotContainKey("recordingUrl");
    }
}
