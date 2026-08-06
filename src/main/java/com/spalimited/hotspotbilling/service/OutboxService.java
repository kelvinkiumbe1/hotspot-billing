package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.OutboundMessage;
import com.spalimited.hotspotbilling.repository.OutboundMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The record of what we sent. Kept separate from the gateways so both SMS
 * and WhatsApp can write to it without knowing about each other, and so a
 * logging failure can never stop a message going out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboundMessageRepository messages;

    /** Logs a send. Never throws — the message already left. */
    public void record(OutboundMessage.Channel channel, String phone, String name, String body,
                       boolean ok, String error, String campaignRef, String sentBy) {
        try {
            messages.save(OutboundMessage.builder()
                    .channel(channel)
                    .recipient(phone)
                    .recipientName(name)
                    .body(body == null ? "" : body.substring(0, Math.min(body.length(), 2000)))
                    .status(ok ? OutboundMessage.Status.SENT : OutboundMessage.Status.FAILED)
                    .error(error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                    .campaignRef(campaignRef)
                    .sentBy(sentBy)
                    .build());
        } catch (Exception e) {
            log.warn("Could not log an outbound message to {}: {}", phone, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(OutboundMessage.Channel channel) {
        List<OutboundMessage> rows = channel == null
                ? messages.findTop500ByOrderByCreatedAtDesc()
                : messages.findByChannelOrderByCreatedAtDesc(channel);
        List<Map<String, Object>> out = new ArrayList<>();
        for (OutboundMessage m : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("channel", m.getChannel());
            row.put("recipient", m.getRecipient());
            row.put("recipientName", m.getRecipientName());
            row.put("body", m.getBody());
            row.put("status", m.getStatus());
            row.put("error", m.getError());
            row.put("cost", m.getCost());
            row.put("campaignRef", m.getCampaignRef());
            row.put("sentBy", m.getSentBy());
            row.put("createdAt", m.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** Headline figures for the outbox: volume, success rate and spend. */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(OutboundMessage.Channel channel) {
        List<OutboundMessage> all = messages.findAll().stream()
                .filter(m -> channel == null || m.getChannel() == channel)
                .toList();

        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant monthAgo = Instant.now().minus(30, ChronoUnit.DAYS);

        long sent = all.stream().filter(m -> m.getStatus() == OutboundMessage.Status.SENT).count();
        long failed = all.stream().filter(m -> m.getStatus() == OutboundMessage.Status.FAILED).count();
        long today = all.stream().filter(m -> m.getCreatedAt().isAfter(dayAgo)).count();
        BigDecimal spend = all.stream()
                .filter(m -> m.getCreatedAt().isAfter(monthAgo))
                .map(OutboundMessage::getCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("today", today);
        out.put("sent", sent);
        out.put("failed", failed);
        // Null rather than 0% when nothing has been sent, so the UI can say so.
        out.put("successPercent", all.isEmpty() ? null
                : BigDecimal.valueOf(sent * 100.0 / all.size())
                        .setScale(0, java.math.RoundingMode.HALF_UP));
        out.put("spend30d", spend);
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<OutboundMessage> find(Long id) {
        return messages.findById(id);
    }
}
