package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.FieldSettings;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.domain.TicketMessage;
import com.spalimited.hotspotbilling.repository.FieldSettingsRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The unattended half of field work.
 *
 * <p>Assigning a job has always sent the technician a text. What never existed
 * was anything that noticed the job afterwards: a technician who accepted a
 * job and then went quiet, or a job that sat in the queue all morning with
 * nobody on it, was only ever caught by an operator happening to look. Both
 * are now chased on a sweep, once each, and the customer is told when the work
 * is actually finished.
 *
 * <p>The conversation itself lives in {@link FieldBotService}; this is the
 * state, the settings and the outbound side.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldOpsService {

    private static final List<SupportTicket.Status> LIVE =
            List.of(SupportTicket.Status.OPEN, SupportTicket.Status.IN_PROGRESS);

    /** Worst first, then oldest first — the order somebody should work them in. */
    private static final Comparator<SupportTicket> WORST_FIRST =
            Comparator.comparing(SupportTicket::getPriority, Comparator.reverseOrder())
                    .thenComparing(SupportTicket::getCreatedAt);

    private final FieldSettingsRepository settingsRepo;
    private final TechnicianRepository technicians;
    private final SupportTicketRepository tickets;
    private final SmsService smsService;
    private final MessagingSettingsService messagingSettings;
    private final PortalSettingsService portalSettings;

    // --- Settings ---

    @Transactional
    public FieldSettings settings() {
        return settingsRepo.findById(FieldSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepo.save(FieldSettings.builder()
                        .id(FieldSettings.SINGLETON_ID)
                        .build()));
    }

    @Transactional
    public FieldSettings update(FieldSettings in) {
        FieldSettings s = settings();
        s.setWhatsappEnabled(in.isWhatsappEnabled());
        s.setStaleJobHours(clamp(in.getStaleJobHours(), 1, 168));
        s.setUnassignedAlertMinutes(clamp(in.getUnassignedAlertMinutes(), 5, 1440));
        s.setNotifyTechniciansOnNewTicket(in.isNotifyTechniciansOnNewTicket());
        s.setDailySummaryEnabled(in.isDailySummaryEnabled());
        s.setDailySummaryHour(clamp(in.getDailySummaryHour(), 0, 23));
        s.setNotifyCustomerOnClose(in.isNotifyCustomerOnClose());
        return settingsRepo.save(s);
    }

    // --- Who is this? ---

    /**
     * The active technician a WhatsApp message came from, if any. Matching is on
     * the phone number held on the technician record, which is the same number
     * the office already texts job assignments to.
     */
    @Transactional(readOnly = true)
    public Optional<Technician> technicianFor(String phone) {
        if (!settings().isWhatsappEnabled()) {
            return Optional.empty();
        }
        String want = normalize(phone);
        if (want == null) {
            return Optional.empty();
        }
        return technicians.findAllByOrderByCreatedAtAsc().stream()
                .filter(Technician::isActive)
                .filter(t -> want.equals(normalize(t.getPhoneNumber())))
                .findFirst();
    }

    // --- Job lookups shared with the bot ---

    @Transactional(readOnly = true)
    public List<SupportTicket> jobsFor(Long technicianId) {
        return tickets.findByStatusInOrderByCreatedAtAsc(LIVE).stream()
                .filter(t -> t.getAssigneeIds().contains(technicianId))
                .sorted(WORST_FIRST)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> unclaimedJobs() {
        return tickets.findByStatusInOrderByCreatedAtAsc(LIVE).stream()
                .filter(t -> t.getAssigneeIds().isEmpty())
                .sorted(WORST_FIRST)
                .toList();
    }

    // --- Outbound ---

    /**
     * Tells a technician a job is theirs, and how to work it from here. Reuses
     * {@link SmsService#trySend} so it goes over WhatsApp when that is
     * configured and falls back to SMS when it is not — the technician gets the
     * job either way, they just cannot reply to a text.
     */
    public void notifyAssignment(SupportTicket ticket, Collection<Long> technicianIds) {
        boolean chat = settings().isWhatsappEnabled();
        for (Long id : technicianIds) {
            technicians.findById(id).ifPresent(t -> textTechnician(t,
                    "🔧 New job #" + ticket.getId() + " — " + ticket.getSubject() + "\n"
                            + ticket.getCustomerName() + " · " + ticket.getPhoneNumber() + "\n"
                            + "Priority: " + ticket.getPriority()
                            + (chat ? "\n\nReply *jobs* here to open it, add notes or close it." : "")));
        }
    }

    /**
     * Sends to a technician on the number held for them, normalised first.
     * The office types numbers as people say them ("0711…"); the gateways only
     * accept 2547XXXXXXXX and drop anything else without complaint, so a
     * technician with a locally-typed number would silently never be told.
     */
    private void textTechnician(Technician tech, String message) {
        String to = normalize(tech.getPhoneNumber());
        if (to == null) {
            log.debug("Technician {} has no usable phone number — not notifying", tech.getUsername());
            return;
        }
        smsService.trySend(to, message);
    }

    /**
     * Tells the technicians a job has arrived and nobody is on it yet.
     *
     * <p>The rest of this class notifies whoever a job was *assigned* to, which
     * is no use for a ticket a customer raised: it has no assignee, so the
     * notification never fired and the ticket waited for an operator to open
     * the dashboard. Anyone active can claim it from the queue.
     *
     * <p>Returns how many technicians could actually be reached, which is not
     * the same as how many exist — a technician whose record holds something
     * that is not a phone number cannot be told anything, and the caller should
     * be able to notice that rather than assume it worked.
     */
    @Transactional(readOnly = true)
    public int notifyNewTicket(SupportTicket ticket) {
        FieldSettings s = settings();
        if (!s.isNotifyTechniciansOnNewTicket()) {
            return 0;
        }
        String body = "🆕 New job in the queue — *" + ticket.getSubject() + "*\n"
                + ticket.getCustomerName() + " · " + ticket.getPhoneNumber() + "\n"
                + "Priority: " + ticket.getPriority()
                + (s.isWhatsappEnabled() ? "\n\nReply *jobs* to take it." : "");

        int reached = 0;
        int unreachable = 0;
        for (Technician t : technicians.findAllByOrderByCreatedAtAsc()) {
            if (!t.isActive()) {
                continue;
            }
            if (normalize(t.getPhoneNumber()) == null) {
                unreachable++;
                continue;
            }
            textTechnician(t, body);
            reached++;
        }
        if (unreachable > 0) {
            log.warn("{} active technician(s) could not be told about job #{} — their record has no "
                    + "usable phone number", unreachable, ticket.getId());
        }
        if (reached == 0) {
            log.warn("Nobody was told about job #{}: no active technician has a usable phone number",
                    ticket.getId());
        }
        return reached;
    }

    /**
     * The message customers actually want: somebody is coming. Costs the
     * technician one tap and heads off the "is anyone even dealing with this"
     * call that otherwise lands on the office.
     */
    public void notifyCustomerOnTheWay(SupportTicket ticket, Technician tech) {
        String phone = ticket.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            return;
        }
        smsService.trySend(phone, "🔧 " + business() + ": " + tech.getFullName()
                + " is on the way to you about \"" + ticket.getSubject() + "\".");
    }

    /** The all-important last step: the customer hears the job is done. */
    public void notifyCustomerClosed(SupportTicket ticket, String note) {
        if (!settings().isNotifyCustomerOnClose()) {
            return;
        }
        String phone = ticket.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            return;
        }
        String body = "✅ " + business() + " — your issue \"" + ticket.getSubject() + "\" has been resolved."
                + (note == null || note.isBlank() ? "" : "\n\nWhat we did: " + note)
                + "\n\nIf it is not right, reply here and we will come back to it.";
        smsService.trySend(phone, body);
    }

    // --- The sweep ---

    /**
     * Chases what has gone quiet. Every branch stamps the ticket it acted on, so
     * a problem that persists is raised once and then left alone rather than
     * pestering somebody every fifteen minutes.
     */
    @Transactional
    public Map<String, Object> runSweep() {
        FieldSettings s = settings();
        Instant now = Instant.now();
        int nudged = 0;
        int escalated = 0;

        for (SupportTicket ticket : tickets.findByStatusInOrderByCreatedAtAsc(LIVE)) {
            if (ticket.getAssigneeIds().isEmpty()) {
                if (ticket.getQueueAlertedAt() == null
                        && olderThan(ticket.getCreatedAt(), now, Duration.ofMinutes(s.getUnassignedAlertMinutes()))) {
                    alertOperator("⚠️ Job #" + ticket.getId() + " has been waiting "
                            + describe(Duration.between(ticket.getCreatedAt(), now))
                            + " with nobody on it.\n"
                            + ticket.getSubject() + " — " + ticket.getCustomerName()
                            + " (" + ticket.getPhoneNumber() + ")");
                    ticket.setQueueAlertedAt(now);
                    tickets.save(ticket);
                    escalated++;
                }
                continue;
            }

            Instant quietSince = lastMovement(ticket);
            if (!olderThan(quietSince, now, Duration.ofHours(s.getStaleJobHours()))) {
                continue;
            }
            // Nudge at most once per stale window, so a genuinely long job is
            // chased occasionally rather than every sweep.
            if (ticket.getLastNudgedAt() != null
                    && !olderThan(ticket.getLastNudgedAt(), now, Duration.ofHours(s.getStaleJobHours()))) {
                continue;
            }
            String quiet = describe(Duration.between(quietSince, now));
            for (Long id : ticket.getAssigneeIds()) {
                technicians.findById(id)
                        .filter(Technician::isActive)
                        .ifPresent(t -> textTechnician(t,
                                "⏰ Job #" + ticket.getId() + " (" + ticket.getSubject() + ") has had no update for "
                                        + quiet + ".\nReply *jobs* to add a note or close it."));
            }
            ticket.setLastNudgedAt(now);
            tickets.save(ticket);
            nudged++;
        }

        int summaries = maybeSendDailySummaries(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nudged", nudged);
        out.put("escalated", escalated);
        out.put("summaries", summaries);
        return out;
    }

    /** Start of day: each technician gets the list of what they are carrying. */
    private int maybeSendDailySummaries(FieldSettings s) {
        if (!s.isDailySummaryEnabled()) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (today.equals(s.getLastSummarySent())
                || LocalTime.now(ZoneId.systemDefault()).getHour() != s.getDailySummaryHour()) {
            return 0;
        }
        int sent = 0;
        for (Technician t : technicians.findAllByOrderByCreatedAtAsc()) {
            if (!t.isActive() || normalize(t.getPhoneNumber()) == null) {
                continue;
            }
            List<SupportTicket> mine = jobsFor(t.getId());
            if (mine.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder("☀️ Good morning " + firstName(t.getFullName())
                    + " — you have " + mine.size() + " open job(s):\n");
            for (SupportTicket ticket : mine) {
                sb.append("• #").append(ticket.getId()).append(' ').append(ticket.getPriority())
                        .append(" — ").append(ticket.getSubject())
                        .append(" (").append(ticket.getCustomerName()).append(")\n");
            }
            sb.append("\nReply *jobs* to work them from here.");
            textTechnician(t, sb.toString());
            sent++;
        }
        s.setLastSummarySent(today);
        settingsRepo.save(s);
        return sent;
    }

    /**
     * When the job last actually moved: the newest staff note, or failing that
     * when someone was put on it. Deliberately not {@code updatedAt}, which any
     * save touches and so would make a reassignment look like progress.
     */
    private static Instant lastMovement(SupportTicket ticket) {
        Instant latestNote = ticket.getMessages().stream()
                .filter(TicketMessage::isFromAdmin)
                .map(TicketMessage::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (latestNote != null) {
            return latestNote;
        }
        return ticket.getWorkStartedAt() != null ? ticket.getWorkStartedAt() : ticket.getCreatedAt();
    }

    private void alertOperator(String message) {
        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, message);
        }
    }

    private String business() {
        String name = portalSettings.settings().getBusinessName();
        return name == null || name.isBlank() ? "Support" : name;
    }

    private static boolean olderThan(Instant when, Instant now, Duration age) {
        return when != null && when.plus(age).isBefore(now);
    }

    static String describe(Duration d) {
        long hours = d.toHours();
        if (hours < 1) {
            return Math.max(1, d.toMinutes()) + "m";
        }
        if (hours < 48) {
            long minutes = d.toMinutes() % 60;
            return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
        }
        return d.toDays() + " days";
    }

    static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Kenyan mobile numbers, however they were typed, as 2547XXXXXXXX. */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 10 && d.startsWith("0")) {
            d = "254" + d.substring(1);
        }
        if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        return d.matches("254\\d{9}") ? d : null;
    }
}
