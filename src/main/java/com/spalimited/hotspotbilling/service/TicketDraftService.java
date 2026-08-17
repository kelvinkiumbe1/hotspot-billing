package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Incident;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.SupportTicket;
import com.spalimited.hotspotbilling.domain.TicketMessage;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.IncidentRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.SupportTicketRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Drafts the first reply to a support ticket.
 *
 * <p>Answering a ticket well takes about thirty seconds of typing and five
 * minutes of looking things up: is this customer paid up, is their area down
 * right now, did we already fix this exact complaint twice last week. All of
 * that is in the database and none of it was ever assembled, so tickets sat
 * unanswered — not because nobody cared but because answering one properly
 * meant opening four screens.
 *
 * <p>So the lookup is done first and the model is given the answers. The two
 * halves are deliberately separate: the facts are gathered here, from the
 * database, and shown to the agent alongside the draft. The model only chooses
 * the wording. If it is off, or fails, the facts are still worth having.
 *
 * <p>Nothing here sends anything. The draft sits on the ticket until a person
 * presses send.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketDraftService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());

    /** Words too common to say anything about what a ticket is about. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "not", "with", "have", "has", "was", "are", "you", "your", "our",
            "this", "that", "there", "here", "from", "since", "been", "very", "please", "help",
            "cannot", "can", "will", "all", "any", "but", "get", "got", "now", "still", "when",
            "what", "why", "how", "its", "it's", "i'm", "am", "is", "my", "me", "we", "at", "on",
            "in", "to", "of", "a", "an", "no", "so", "up", "out", "off");

    private final SupportTicketRepository tickets;
    private final SubscriberRepository subscribers;
    private final VoucherRepository vouchers;
    private final RouterRepository routers;
    private final IncidentRepository incidents;
    private final AiService ai;
    private final AiSettingsService aiSettings;
    private final PortalSettingsService portalSettings;

    /**
     * The draft, and the facts it was written from. {@code drafted} is false
     * when the model was off or failed — the facts are still returned, and are
     * still the useful half.
     */
    public record Draft(String draft, List<String> basis, boolean drafted, String error) {
    }

    /**
     * Builds (and stores) a draft for one ticket. The facts come back whether
     * or not the model does, because half the value is knowing that the
     * customer complaining about a dead connection lapsed four days ago.
     */
    @Transactional
    public Draft draft(Long ticketId) {
        SupportTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ticket: " + ticketId));
        List<String> basis = gatherFacts(ticket);

        String text = null;
        String error = null;
        try {
            text = compose(ticket, basis);
        } catch (Exception e) {
            error = e.getMessage();
            log.debug("Could not draft a reply for ticket {}: {}", ticketId, e.getMessage());
        }

        ticket.setAiDraftTriedAt(Instant.now());
        ticket.setAiDraftBasis(trim(String.join("\n", basis), 1200));
        if (text != null && !text.isBlank()) {
            ticket.setAiDraft(trim(text, 2000));
            ticket.setAiDraftedAt(Instant.now());
        }
        tickets.save(ticket);
        return new Draft(ticket.getAiDraft(), basis, text != null && !text.isBlank(), error);
    }

    /**
     * Drafts for everything that has come in and not been answered. Bounded per
     * pass: this costs the operator money per call, and a burst of tickets
     * should spread over a few minutes rather than empty their credit at once.
     */
    @Transactional
    public int draftPending(int limit) {
        if (!aiSettings.get().isDraftTicketReplies() || !ai.isEnabled()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        List<SupportTicket> pending = tickets.findTop100ByOrderByUpdatedAtDesc().stream()
                .filter(t -> t.getStatus() != SupportTicket.Status.RESOLVED)
                .filter(t -> t.getCreatedAt().isAfter(cutoff))
                .filter(t -> t.getAiDraftTriedAt() == null)
                // Somebody has already replied; they did not need the help.
                .filter(t -> t.getMessages().stream().noneMatch(TicketMessage::isFromAdmin))
                .sorted(Comparator.comparing(SupportTicket::getCreatedAt))
                .limit(limit)
                .toList();

        int done = 0;
        for (SupportTicket t : pending) {
            try {
                draft(t.getId());
                done++;
            } catch (Exception e) {
                log.warn("Drafting ticket {} failed: {}", t.getId(), e.getMessage());
            }
        }
        return done;
    }

    // --- The facts ---

    /**
     * Everything the database already knows that bears on this complaint. Each
     * line is written to be read by a person as much as by the model.
     */
    private List<String> gatherFacts(SupportTicket ticket) {
        List<String> facts = new ArrayList<>();
        String phone = normalize(ticket.getPhoneNumber());

        Subscriber sub = phone == null ? null
                : subscribers.findByPhoneNumber(phone).stream().findFirst().orElse(null);
        if (sub != null) {
            boolean paid = sub.getPaidUntil() != null && sub.getPaidUntil().isAfter(Instant.now());
            facts.add("Account: home/office subscriber \"" + sub.getFullName() + "\", "
                    + sub.getStatus() + ", "
                    + (sub.getPaidUntil() == null ? "never paid"
                            : (paid ? "paid until " : "expired on ") + DATE.format(sub.getPaidUntil()))
                    + (sub.getBandwidth() == null ? "" : ", " + sub.getBandwidth() + " package"));
            if (!paid) {
                facts.add("NOTE: their subscription is not currently paid — that alone would explain "
                        + "a dead connection.");
            }
            if (sub.getRouterId() != null) {
                routers.findById(sub.getRouterId()).ifPresent(r -> facts.add(
                        "Their router: " + r.getName() + " is currently "
                                + (r.isOnline() ? "online" : "OFFLINE")));
            }
        } else if (phone != null) {
            Voucher v = vouchers.findByPhoneNumberOrderByCreatedAtDesc(phone).stream()
                    .findFirst().orElse(null);
            if (v != null) {
                facts.add("Account: hotspot customer. Latest code " + v.getCode() + " is " + v.getStatus()
                        + (v.getExpiresAt() == null ? ""
                                : ", " + (v.getExpiresAt().isAfter(Instant.now()) ? "runs until " : "ran out ")
                                        + DATE.format(v.getExpiresAt()))
                        + (v.getPlan() == null ? "" : " (" + v.getPlan().getName() + ")"));
            } else {
                facts.add("Account: no subscriber or voucher found on " + ticket.getPhoneNumber()
                        + " — they may have paid from a different number.");
            }
        }

        List<Incident> open = incidents.findByStatusOrderByStartedAtDesc(Incident.Status.OPEN);
        if (!open.isEmpty()) {
            Incident i = open.get(0);
            // Whether it is *their* outage matters: apologising for a network
            // fault to someone whose own router is fine reads as an excuse.
            boolean theirs = sub != null && sub.getRouterId() != null
                    && i.getRouterIds().contains(sub.getRouterId());
            facts.add("Network: an outage is open right now — \"" + i.getTitle() + "\", running "
                    + human(i.getDuration()) + (theirs ? ". THIS CUSTOMER IS AFFECTED BY IT."
                            : ". This customer does not appear to be in the affected area."));
        } else {
            long down = routers.findByEnabledTrue().stream().filter(r -> !r.isOnline()).count();
            facts.add(down == 0 ? "Network: everything is online right now."
                    : "Network: " + down + " router(s) are offline right now.");
        }

        List<String> priors = similarResolved(ticket);
        if (!priors.isEmpty()) {
            facts.add("What fixed tickets like this before:");
            facts.addAll(priors);
        }
        return facts;
    }

    /**
     * Up to three recently resolved tickets whose subject shares distinctive
     * words with this one, and what closed them. This is the part an agent
     * cannot do quickly by hand and the part that most often holds the answer.
     */
    private List<String> similarResolved(SupportTicket ticket) {
        Set<String> want = keywords(ticket.getSubject());
        if (want.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        tickets.findTop100ByOrderByUpdatedAtDesc().stream()
                .filter(t -> !t.getId().equals(ticket.getId()))
                .filter(t -> t.getStatus() == SupportTicket.Status.RESOLVED)
                .filter(t -> {
                    Set<String> theirs = keywords(t.getSubject());
                    theirs.retainAll(want);
                    return !theirs.isEmpty();
                })
                .sorted(Comparator.comparing(SupportTicket::getUpdatedAt).reversed())
                .limit(3)
                .forEach(t -> {
                    String closing = t.getMessages().stream()
                            .filter(TicketMessage::isFromAdmin)
                            .reduce((a, b) -> b)
                            .map(TicketMessage::getBody)
                            .orElse(null);
                    out.add("  • \"" + t.getSubject() + "\" → "
                            + (closing == null || closing.isBlank()
                                    ? "closed with no note" : trim(closing, 220)));
                });
        return out;
    }

    private static Set<String> keywords(String subject) {
        Set<String> out = new LinkedHashSet<>();
        if (subject == null) {
            return out;
        }
        for (String word : subject.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
            if (word.length() >= 3 && !STOPWORDS.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    // --- The wording ---

    private String compose(SupportTicket ticket, List<String> basis) {
        String business = portalSettings.settings().getBusinessName();
        if (business == null || business.isBlank()) {
            business = "our team";
        }

        String system = "You write the first reply to a customer's support message for " + business
                + ", an internet provider in Kenya. Write the reply itself and nothing else — no "
                + "subject line, no preamble, no options to choose between.\n"
                + "Rules:\n"
                + "- Under 60 words. It is read on a phone.\n"
                + "- Warm, plain, direct. Kenyan English. No corporate filler.\n"
                + "- Use the FACTS below. Never state anything that is not in them — no invented "
                + "times, causes, engineer names or ticket numbers.\n"
                + "- If the facts show the customer has not paid or has expired, say so kindly and "
                + "tell them paying restores it.\n"
                + "- If the facts show an outage this customer IS affected by, acknowledge it. If the "
                + "outage does not affect them, do not mention it at all.\n"
                + "- Never promise a refund, a discount, a compensation amount, or a specific arrival "
                + "time. An agent has not agreed to any of those.\n"
                + "- If the facts do not explain the problem, ask the one question that would.\n"
                + "- Sign off as " + business + ".";

        StringBuilder user = new StringBuilder("FACTS:\n");
        for (String f : basis) {
            user.append("- ").append(f).append('\n');
        }
        user.append("\nCUSTOMER: ").append(ticket.getCustomerName())
                .append("\nSUBJECT: ").append(ticket.getSubject()).append('\n');
        ticket.getMessages().stream()
                .filter(m -> !m.isFromAdmin())
                .findFirst()
                .ifPresent(m -> user.append("THEY WROTE: ").append(trim(m.getBody(), 800)).append('\n'));

        return ai.chat(system, user.toString(), 220, 0.4);
    }

    private static String human(Duration d) {
        long hours = d.toHours();
        return hours < 1 ? Math.max(1, d.toMinutes()) + " minutes"
                : hours + "h " + (d.toMinutes() % 60) + "m";
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String normalize(String raw) {
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
