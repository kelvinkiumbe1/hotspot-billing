package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.MaintenanceEvent;
import com.spalimited.hotspotbilling.repository.MaintenanceEventRepository;
import com.spalimited.hotspotbilling.repository.TaskNoteRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** Admin maintenance calendar (HTTP Basic, ADMIN role). */
@RestController
@RequestMapping("/api/admin/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceEventRepository events;
    private final TaskNoteRepository taskNotes;

    public record MaintenanceRequest(
            @NotBlank String title,
            String description,
            @NotNull Instant scheduledStart,
            @NotNull Instant scheduledEnd,
            Integer estimatedDowntimeMinutes) {
    }

    @GetMapping
    public List<MaintenanceEvent> all() {
        return events.findAllByOrderByScheduledStartAsc();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceEvent create(@Valid @RequestBody MaintenanceRequest request) {
        return events.save(MaintenanceEvent.builder()
                .title(request.title())
                .description(request.description())
                .scheduledStart(request.scheduledStart())
                .scheduledEnd(request.scheduledEnd())
                .estimatedDowntimeMinutes(request.estimatedDowntimeMinutes())
                .build());
    }

    @PatchMapping("/{id}/complete")
    public MaintenanceEvent complete(@PathVariable Long id) {
        MaintenanceEvent event = events.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown maintenance event: " + id));
        event.setStatus(MaintenanceEvent.Status.COMPLETED);
        return events.save(event);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) {
        taskNotes.deleteByEventId(id);
        events.deleteById(id);
    }
}
