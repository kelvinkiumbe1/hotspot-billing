package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.DirectMessage;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.DirectMessageRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import com.spalimited.hotspotbilling.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct messages between technicians and the admin — one channel per
 * technician, independent of tasks. Technicians use /api/tech/messages;
 * the admin sees all channels under /api/admin/messages.
 */
@RestController
@RequiredArgsConstructor
public class MessageController {

    private final DirectMessageRepository messages;
    private final TechnicianRepository technicians;
    private final FileStorageService storage;

    private DirectMessage saveMessage(String technician, boolean fromAdmin, String author,
                                      String message, MultipartFile photo) throws IOException {
        boolean hasText = message != null && !message.isBlank();
        boolean hasPhoto = photo != null && !photo.isEmpty();
        if (!hasText && !hasPhoto) {
            throw new IllegalArgumentException("A message needs text, a photo, or both");
        }
        return messages.save(DirectMessage.builder()
                .technician(technician)
                .fromAdmin(fromAdmin)
                .author(author)
                .body(hasText ? message.trim() : null)
                .photoFilename(hasPhoto ? storage.storeImage(photo) : null)
                .build());
    }

    // --- Technician side ---

    /** The technician's conversation with the admin; opening it marks admin messages read. */
    @GetMapping("/api/tech/messages")
    @Transactional
    public List<DirectMessage> myMessages(Principal principal) {
        List<DirectMessage> channel = messages.findByTechnicianOrderByCreatedAtAsc(principal.getName());
        channel.stream()
                .filter(m -> m.isFromAdmin() && !m.isReadByRecipient())
                .forEach(m -> m.setReadByRecipient(true));
        return channel;
    }

    /** Unread count for the technician's nav badge (no side effects). */
    @GetMapping("/api/tech/messages/unread")
    public Map<String, Long> myUnread(Principal principal) {
        return Map.of("unread",
                messages.countByTechnicianAndFromAdminTrueAndReadByRecipientFalse(principal.getName()));
    }

    @PostMapping(value = "/api/tech/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DirectMessage send(
            @RequestParam(required = false) String message,
            @RequestParam(required = false) MultipartFile photo,
            Principal principal) throws IOException {
        return saveMessage(principal.getName(), false, principal.getName(), message, photo);
    }

    // --- Admin side ---

    /** One row per technician: last message, unread count. Sorted, most recent first. */
    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @GetMapping("/api/admin/messages/channels")
    public List<Map<String, Object>> channels() {
        return technicians.findAllByOrderByCreatedAtAsc().stream()
                .map(t -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("username", t.getUsername());
                    row.put("fullName", t.getFullName());
                    row.put("active", t.isActive());
                    row.put("unread", messages.countByTechnicianAndFromAdminFalseAndReadByRecipientFalse(t.getUsername()));
                    messages.findTop1ByTechnicianOrderByCreatedAtDesc(t.getUsername())
                            .ifPresent(m -> row.put("lastMessage", m));
                    return row;
                })
                .sorted((a, b) -> {
                    DirectMessage ma = (DirectMessage) a.get("lastMessage");
                    DirectMessage mb = (DirectMessage) b.get("lastMessage");
                    if (ma == null && mb == null) return 0;
                    if (ma == null) return 1;
                    if (mb == null) return -1;
                    return mb.getCreatedAt().compareTo(ma.getCreatedAt());
                })
                .toList();
    }

    /** A technician's channel; opening it marks their messages read. */
    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @GetMapping("/api/admin/messages/{technician}")
    @Transactional
    public List<DirectMessage> channel(@PathVariable String technician) {
        List<DirectMessage> channel = messages.findByTechnicianOrderByCreatedAtAsc(technician);
        channel.stream()
                .filter(m -> !m.isFromAdmin() && !m.isReadByRecipient())
                .forEach(m -> m.setReadByRecipient(true));
        return channel;
    }

    @PreAuthorize("hasAuthority('CUSTOMERS')")
    @PostMapping(value = "/api/admin/messages/{technician}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DirectMessage reply(
            @PathVariable String technician,
            @RequestParam(required = false) String message,
            @RequestParam(required = false) MultipartFile photo,
            Principal principal) throws IOException {
        Technician target = technicians.findByUsername(technician)
                .orElseThrow(() -> new IllegalArgumentException("Unknown technician: " + technician));
        return saveMessage(target.getUsername(), true, principal.getName(), message, photo);
    }
}
