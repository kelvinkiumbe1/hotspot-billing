package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.DirectMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, Long> {

    List<DirectMessage> findByTechnicianOrderByCreatedAtAsc(String technician);

    Optional<DirectMessage> findTop1ByTechnicianOrderByCreatedAtDesc(String technician);

    /** Unread messages the technician sent, per channel (admin's badge). */
    long countByTechnicianAndFromAdminFalseAndReadByRecipientFalse(String technician);

    /** Unread messages the admin sent to this technician (technician's badge). */
    long countByTechnicianAndFromAdminTrueAndReadByRecipientFalse(String technician);
}
