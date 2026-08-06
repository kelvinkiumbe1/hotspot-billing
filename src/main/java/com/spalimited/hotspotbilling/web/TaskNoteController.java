package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.TaskNote;
import com.spalimited.hotspotbilling.repository.MaintenanceEventRepository;
import com.spalimited.hotspotbilling.repository.TaskNoteRepository;
import com.spalimited.hotspotbilling.service.FileStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

/**
 * Comment/photo thread on a maintenance task. Reachable by both roles
 * (/api/tech/** allows ADMIN and TECHNICIAN): technicians post site
 * updates and photos from the field, admins reply from the maintenance
 * calendar detail panel.
 */
@RestController
@RequiredArgsConstructor
public class TaskNoteController {

    private final TaskNoteRepository notes;
    private final MaintenanceEventRepository events;
    private final FileStorageService storage;

    @GetMapping("/api/tech/tasks/{id}/notes")
    public List<TaskNote> list(@PathVariable Long id) {
        return notes.findByEventIdOrderByCreatedAtAsc(id);
    }

    @PostMapping(value = "/api/tech/tasks/{id}/notes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TaskNote add(
            @PathVariable Long id,
            @RequestParam(required = false) String message,
            @RequestParam(required = false) MultipartFile photo,
            Principal principal,
            HttpServletRequest request) throws IOException {

        events.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown task: " + id));

        boolean hasText = message != null && !message.isBlank();
        boolean hasPhoto = photo != null && !photo.isEmpty();
        if (!hasText && !hasPhoto) {
            throw new IllegalArgumentException("A note needs a message, a photo, or both");
        }

        return notes.save(TaskNote.builder()
                .eventId(id)
                .author(principal.getName())
                .fromAdmin(request.isUserInRole("ADMIN"))
                .body(hasText ? message.trim() : null)
                .photoFilename(hasPhoto ? storage.storeImage(photo) : null)
                .build());
    }
}
