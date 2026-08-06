package com.spalimited.hotspotbilling.repository;

import com.spalimited.hotspotbilling.domain.TaskNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskNoteRepository extends JpaRepository<TaskNote, Long> {

    List<TaskNote> findByEventIdOrderByCreatedAtAsc(Long eventId);

    void deleteByEventId(Long eventId);
}
