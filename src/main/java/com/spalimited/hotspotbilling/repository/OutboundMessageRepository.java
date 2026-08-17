package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.OutboundMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {

    List<OutboundMessage> findTop500ByOrderByCreatedAtDesc();

    List<OutboundMessage> findByChannelOrderByCreatedAtDesc(OutboundMessage.Channel channel);

    long countByStatus(OutboundMessage.Status status);

    long countByCreatedAtAfter(Instant since);

    List<OutboundMessage> findByCreatedAtAfter(Instant since);

    /** What the system has tried to send one number lately, oldest first. */
    List<OutboundMessage> findByRecipientAndCreatedAtAfterOrderByCreatedAtAsc(
            String recipient, Instant since);
}
