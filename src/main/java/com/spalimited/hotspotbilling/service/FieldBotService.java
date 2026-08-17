package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.domain.TicketMessage;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Field Connect in a chat. A technician works their jobs from the WhatsApp
 * thread they already get assignments in: see what is on them, take something
 * from the queue, tell the customer they are on the way, leave a note, close
 * the job.
 *
 * <p>The office side of this has always existed in the app; the app is the
 * problem. Someone up a pole with one hand free will send a WhatsApp message
 * and will not open a web app, so the job stayed "in progress" until they got
 * back to the van. Same data, same rules — only the way in is different.
 *
 * <p>State is per-phone and in memory, like the customer bot: a lost session
 * costs one extra *menu*, which is cheaper than a table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldBotService {

    private static final Duration TTL = Duration.ofMinutes(30);

    private enum Step { MENU, JOB_PICK, JOB, NOTE, DONE_NOTE, QUEUE_PICK }

    private static final class Session {
        Step step = Step.MENU;
        Long jobId;
        List<Long> listed = new ArrayList<>();
        Instant touched = Instant.now();
    }

    private final FieldOpsService fieldOps;
    private final SupportTicketRepository tickets;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** True when this number belongs to an active technician and chat is on. */
    public boolean handles(String phone) {
        return fieldOps.technicianFor(phone).isPresent();
    }

    /**
     * Works out the reply for a technician's message. Returns null when the
     * number is not a technician's, so the caller can fall through to the
     * customer bot.
     */
    @Transactional
    public String reply(String fromPhone, String rawText) {
        Technician tech = fieldOps.technicianFor(fromPhone).orElse(null);
        if (tech == null) {
            return null;
        }
        String text = rawText == null ? "" : rawText.trim();
        String lower = text.toLowerCase();
        Session s = session(fromPhone);

        if (lower.isEmpty() || lower.matches("menu|hi|hello|start|0|hey|habari|jobs|job")) {
            reset(s);
            return menu(tech);
        }

        return switch (s.step) {
            case MENU -> handleMenu(s, tech, text);
            case JOB_PICK -> handlePick(s, tech, text, false);
            case QUEUE_PICK -> handlePick(s, tech, text, true);
            case JOB -> handleJobAction(s, tech, text);
            case NOTE -> handleNote(s, tech, text);
            case DONE_NOTE -> handleDone(s, tech, text);
        };
    }

    // --- Menu ---

    private String menu(Technician tech) {
        int mine = fieldOps.jobsFor(tech.getId()).size();
        int queue = fieldOps.unclaimedJobs().size();
        return "👷 *Field Connect* — " + FieldOpsService.firstName(tech.getFullName()) + "\n\n"
                + "*1* My jobs (" + mine + ")\n"
                + "*2* Open queue (" + queue + ")\n"
                + "*3* What I closed today\n\n"
                + "_Reply *menu* at any time._";
    }

    private String handleMenu(Session s, Technician tech, String text) {
        switch (text.trim()) {
            case "1" -> {
                List<SupportTicket> mine = fieldOps.jobsFor(tech.getId());
                if (mine.isEmpty()) {
                    return "🎉 Nothing open on you right now.\nReply *2* to take something from the queue.";
                }
                s.step = Step.JOB_PICK;
                return list(s, mine, "*Your jobs*");
            }
            case "2" -> {
                List<SupportTicket> queue = fieldOps.unclaimedJobs();
                if (queue.isEmpty()) {
                    return "The queue is empty — everything has someone on it.\nReply *menu*.";
                }
                s.step = Step.QUEUE_PICK;
                return list(s, queue, "*Waiting for someone*");
            }
            case "3" -> {
                return closedToday(tech);
            }
            default -> {
                return "Sorry, I didn't get that.\n\n" + menu(tech);
            }
        }
    }

    private String list(Session s, List<SupportTicket> jobs, String heading) {
        s.listed.clear();
        StringBuilder sb = new StringBuilder(heading).append('\n');
        for (int i = 0; i < jobs.size(); i++) {
            SupportTicket t = jobs.get(i);
            s.listed.add(t.getId());
            sb.append(i + 1).append(") *#").append(t.getId()).append("* ")
                    .append(shortPriority(t.getPriority())).append(" — ").append(t.getSubject())
                    .append("\n    ").append(t.getCustomerName())
                    .append(" · waiting ")
                    .append(FieldOpsService.describe(Duration.between(t.getCreatedAt(), Instant.now())))
                    .append('\n');
        }
        return sb.append("\nReply with a number to open it, or *menu*.").toString();
    }

    /** Opening one of the numbered jobs; from the queue, that also claims it. */
    private String handlePick(Session s, Technician tech, String text, boolean claiming) {
        Integer idx = asInt(text);
        if (idx == null || idx < 1 || idx > s.listed.size()) {
            return "Reply with one of the numbers in the list, or *menu*.";
        }
        SupportTicket ticket = tickets.findById(s.listed.get(idx - 1)).orElse(null);
        if (ticket == null) {
            reset(s);
            return "That job has gone. Reply *menu*.";
        }
        if (claiming) {
            if (!ticket.getAssigneeIds().isEmpty()) {
                // Somebody took it between the list being sent and this reply.
                reset(s);
                return "Someone else already picked up #" + ticket.getId() + ". Reply *2* for what's left.";
            }
            ticket.getAssigneeIds().add(tech.getId());
            ticket.setStatus(SupportTicket.Status.IN_PROGRESS);
            if (ticket.getWorkStartedAt() == null) {
                ticket.setWorkStartedAt(Instant.now());
            }
            ticket = tickets.save(ticket);
            log.info("Technician {} claimed ticket {} over WhatsApp", tech.getUsername(), ticket.getId());
        }
        s.jobId = ticket.getId();
        s.step = Step.JOB;
        return card(ticket, claiming);
    }

    private String card(SupportTicket t, boolean justClaimed) {
        String lastNote = t.getMessages().stream()
                .filter(TicketMessage::isFromAdmin)
                .reduce((a, b) -> b)
                .map(TicketMessage::getBody)
                .orElse(null);
        String opening = t.getMessages().stream()
                .filter(m -> !m.isFromAdmin())
                .findFirst()
                .map(TicketMessage::getBody)
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        if (justClaimed) {
            sb.append("✅ Job #").append(t.getId()).append(" is yours.\n\n");
        }
        sb.append("*#").append(t.getId()).append(" · ").append(t.getSubject()).append("*\n")
                .append(t.getCustomerName()).append(" · ").append(t.getPhoneNumber()).append('\n')
                .append(shortPriority(t.getPriority())).append(" · ").append(t.getStatus()).append('\n');
        if (t.getWorkStartedAt() != null) {
            sb.append("On the job: ")
                    .append(FieldOpsService.describe(Duration.between(t.getWorkStartedAt(), Instant.now())))
                    .append('\n');
        }
        if (opening != null && !opening.isBlank()) {
            sb.append("\n_Reported:_ ").append(trim(opening, 200)).append('\n');
        }
        if (lastNote != null && !lastNote.isBlank()) {
            sb.append("_Last note:_ ").append(trim(lastNote, 200)).append('\n');
        }
        return sb.append("\n*1* Add a note\n")
                .append("*2* Tell the customer I'm on my way\n")
                .append("*3* Mark it done\n")
                .append("*4* Raise the priority\n")
                .append("*0* Back")
                .toString();
    }

    private String handleJobAction(Session s, Technician tech, String text) {
        SupportTicket ticket = openJob(s);
        if (ticket == null) {
            reset(s);
            return "That job isn't open any more. Reply *menu*.";
        }
        switch (text.trim()) {
            case "1" -> {
                s.step = Step.NOTE;
                return "Type your note for job #" + ticket.getId() + " in one message.";
            }
            case "2" -> {
                fieldOps.notifyCustomerOnTheWay(ticket, tech);
                ticket.getMessages().add(TicketMessage.builder()
                        .ticket(ticket)
                        .fromAdmin(true)
                        .body(tech.getFullName() + " is on the way (told the customer)")
                        .build());
                tickets.save(ticket);
                return "📣 Told " + ticket.getCustomerName() + " you're on your way.\n\n" + card(ticket, false);
            }
            case "3" -> {
                s.step = Step.DONE_NOTE;
                return "What did you do to fix it? Send it in one message — the customer sees this."
                        + "\n\nReply *skip* to close without a note.";
            }
            case "4" -> {
                if (ticket.getPriority() == SupportTicket.Priority.HIGH) {
                    return "#" + ticket.getId() + " is already HIGH.\n\n" + card(ticket, false);
                }
                ticket.setPriority(ticket.getPriority() == SupportTicket.Priority.LOW
                        ? SupportTicket.Priority.MEDIUM : SupportTicket.Priority.HIGH);
                ticket = tickets.save(ticket);
                return "⬆️ Raised to " + ticket.getPriority() + ".\n\n" + card(ticket, false);
            }
            case "0" -> {
                reset(s);
                return menu(tech);
            }
            default -> {
                return "Reply *1*–*4*, or *0* to go back.";
            }
        }
    }

    private String handleNote(Session s, Technician tech, String text) {
        SupportTicket ticket = openJob(s);
        if (ticket == null) {
            reset(s);
            return "That job isn't open any more. Reply *menu*.";
        }
        ticket.getMessages().add(TicketMessage.builder()
                .ticket(ticket)
                .fromAdmin(true)
                .body(tech.getFullName() + ": " + text)
                // Stamped here as well as on persist, so the sweep can read the
                // time back before the transaction has flushed.
                .createdAt(Instant.now())
                .build());
        // A note is movement, so the stale-job chase starts counting again.
        ticket.setLastNudgedAt(null);
        ticket = tickets.save(ticket);
        s.step = Step.JOB;
        return "📝 Noted on #" + ticket.getId() + ".\n\n" + card(ticket, false);
    }

    private String handleDone(Session s, Technician tech, String text) {
        SupportTicket ticket = openJob(s);
        if (ticket == null) {
            reset(s);
            return "That job isn't open any more. Reply *menu*.";
        }
        String note = "skip".equalsIgnoreCase(text.trim()) ? null : text.trim();
        if (note != null) {
            ticket.getMessages().add(TicketMessage.builder()
                    .ticket(ticket)
                    .fromAdmin(true)
                    .body(tech.getFullName() + ": " + note)
                    .build());
        }
        ticket.setStatus(SupportTicket.Status.RESOLVED);
        ticket.setResolvedAt(Instant.now());
        ticket.setResolvedBy(tech.getFullName());
        SupportTicket saved = tickets.save(ticket);
        fieldOps.notifyCustomerClosed(saved, note);
        reset(s);

        String took = saved.getWorkingMinutes() == null ? ""
                : " (" + FieldOpsService.describe(Duration.ofMinutes(saved.getWorkingMinutes())) + " on the job)";
        log.info("Technician {} closed ticket {} over WhatsApp", tech.getUsername(), saved.getId());
        return "✅ Job #" + saved.getId() + " closed" + took + ". The customer has been told.\n\n"
                + menu(tech);
    }

    private String closedToday(Technician tech) {
        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        long n = tickets.countByResolvedByAndResolvedAtAfter(tech.getFullName(), startOfDay);
        return n == 0
                ? "Nothing closed yet today. Reply *1* for your jobs."
                : "🏁 You've closed *" + n + "* job(s) today. Nice one.\nReply *menu*.";
    }

    // --- Plumbing ---

    private SupportTicket openJob(Session s) {
        return s.jobId == null ? null
                : tickets.findById(s.jobId)
                        .filter(t -> t.getStatus() != SupportTicket.Status.RESOLVED)
                        .orElse(null);
    }

    private Session session(String phone) {
        Session s = sessions.compute(phone, (k, v) ->
                v == null || Instant.now().isAfter(v.touched.plus(TTL)) ? new Session() : v);
        s.touched = Instant.now();
        return s;
    }

    private static void reset(Session s) {
        s.step = Step.MENU;
        s.jobId = null;
        s.listed.clear();
    }

    private static String shortPriority(SupportTicket.Priority p) {
        return switch (p) {
            case HIGH -> "🔴 HIGH";
            case MEDIUM -> "🟠 MED";
            case LOW -> "🟢 LOW";
        };
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Integer asInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
