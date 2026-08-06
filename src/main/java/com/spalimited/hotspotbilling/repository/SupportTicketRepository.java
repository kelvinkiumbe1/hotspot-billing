package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findTop100ByOrderByUpdatedAtDesc();
}
