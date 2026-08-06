package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.OutboundMessage;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.service.AudienceService;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.OutboxService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.SmsService;
import com.spalimited.hotspotbilling.service.WhatsappService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

/**
 * Outbound customer messaging: the log of what went out, and composing a
 * new send to a chosen audience.
 */
@RestController
@RequestMapping("/api/admin/comms")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('OUTREACH')")
public class CommsController {

    private final OutboxService outboxService;
    private final AudienceService audienceService;
    private final SmsService smsService;
    private final WhatsappService whatsappService;
    private final RouterRepository routers;
    private final PortalSettingsService portalSettingsService;
    private final AuditService audit;

    /** Values a message body may use, shown as chips in the composer. */
    private static final List<String> VARIABLES = List.of(
            "first_name", "last_name", "phone", "package_name",
            "company_name", "expiry_date", "expiry_at");

    private static OutboundMessage.Channel channel(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw)) {
            return null;
        }
        return OutboundMessage.Channel.valueOf(raw.toUpperCase());
    }

    @GetMapping("/outbox")
    public List<Map<String, Object>> outbox(@RequestParam(required = false) String channel) {
        return outboxService.list(channel(channel));
    }

    @GetMapping("/outbox/stats")
    public Map<String, Object> stats(@RequestParam(required = false) String channel) {
        return outboxService.stats(channel(channel));
    }

    /** What the composer needs to draw itself: audiences, channels, variables. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segments", audienceService.segmentCounts());
        out.put("routers", routers.findAll().stream()
                .map(r -> Map.<String, Object>of("id", r.getId(), "name", r.getName()))
                .toList());
        out.put("everyoneCount", audienceService.everyone().size());
        out.put("variables", VARIABLES);
        out.put("whatsappEnabled", whatsappService.isEnabled());
        out.put("smsEnabled", smsService.isEnabled());
        return out;
    }

    public record SendRequest(
            String channel,
            /** specific | segments | routers | everyone */
            @NotBlank String audience,
            List<String> phones,
            List<String> segments,
            List<Long> routerIds,
            @NotBlank @Size(max = 1600) String body,
            boolean dryRun) {
    }

    /**
     * Resolves the audience, personalises the body per recipient and sends.
     * A dry run resolves and previews without sending a thing, so a campaign
     * can be checked before it costs money.
     */
    @PostMapping("/send")
    public Map<String, Object> send(@Valid @RequestBody SendRequest request, Principal principal) {
        List<AudienceService.Recipient> recipients = resolve(request);
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("That audience has nobody in it — pick a different one");
        }

        String business = portalSettingsService.settings().getBusinessName();
        // company_name is not per-recipient, so it is filled once here.
        String body = request.body().replace("@company_name", business == null ? "" : business);

        if (request.dryRun()) {
            List<Map<String, Object>> preview = recipients.stream()
                    .limit(5)
                    .map(r -> Map.<String, Object>of(
                            "phone", r.phone(),
                            "name", r.name() == null ? "" : r.name(),
                            "body", audienceService.personalise(body, r)))
                    .toList();
            return Map.of("recipients", recipients.size(), "preview", preview, "sent", 0, "dryRun", true);
        }

        // Refuse up front rather than writing one failure row per recipient —
        // a campaign to 500 people would otherwise fill the outbox with noise.
        if (!whatsappService.isEnabled() && !smsService.isEnabled()) {
            throw new IllegalStateException(
                    "No messaging gateway is configured yet — add WhatsApp or SMS credentials in Settings. "
                            + "Use Preview to check the audience and wording in the meantime.");
        }

        String campaignRef = "CMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        for (AudienceService.Recipient r : recipients) {
            smsService.trySend(r.phone(), audienceService.personalise(body, r),
                    r.name(), campaignRef, principal.getName());
        }
        audit.record(principal, "comms.send",
                "Sent " + campaignRef + " to " + recipients.size() + " recipient(s)");
        log.info("{} dispatched to {} recipient(s)", campaignRef, recipients.size());
        return Map.of("recipients", recipients.size(), "sent", recipients.size(),
                "campaignRef", campaignRef, "dryRun", false);
    }

    private List<AudienceService.Recipient> resolve(SendRequest request) {
        return switch (request.audience()) {
            case "specific" -> audienceService.forPhones(
                    request.phones() == null ? List.of() : request.phones());
            case "segments" -> {
                List<AudienceService.Recipient> all = new ArrayList<>();
                for (String segment : request.segments() == null ? List.<String>of() : request.segments()) {
                    all.addAll(audienceService.forSegment(segment));
                }
                yield audienceService.dedupe(all);
            }
            case "routers" -> {
                List<AudienceService.Recipient> all = new ArrayList<>();
                for (Long routerId : request.routerIds() == null ? List.<Long>of() : request.routerIds()) {
                    all.addAll(audienceService.forRouter(routerId));
                }
                yield audienceService.dedupe(all);
            }
            case "everyone" -> audienceService.everyone();
            default -> throw new IllegalArgumentException("Unknown audience: " + request.audience());
        };
    }

    /** Retries one failed message to the same number with the same body. */
    @PostMapping("/outbox/{id}/resend")
    public Map<String, Object> resend(@PathVariable Long id, Principal principal) {
        OutboundMessage original = outboxService.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown message: " + id));
        if (original.getStatus() != OutboundMessage.Status.FAILED) {
            throw new IllegalStateException("That message went out fine — there is nothing to retry");
        }
        smsService.trySend(original.getRecipient(), original.getBody(),
                original.getRecipientName(), original.getCampaignRef(), principal.getName());
        audit.record(principal, "comms.resend", "Retried message to " + original.getRecipient());
        return Map.of("message", "Retried — check the outbox for the result");
    }
}
