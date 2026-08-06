package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.AuditEvent;
import com.spalimited.hotspotbilling.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.Principal;

/** Records admin/technician actions for the audit log. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditEventRepository events;

    public void record(String actor, String action, String detail) {
        try {
            events.save(AuditEvent.builder()
                    .actor(actor == null || actor.isBlank() ? "system" : actor)
                    .action(action)
                    .detail(detail.length() > 500 ? detail.substring(0, 500) : detail)
                    .build());
        } catch (Exception e) {
            log.warn("Could not write audit event {}: {}", action, e.getMessage());
        }
    }

    public void record(Principal principal, String action, String detail) {
        record(principal != null ? principal.getName() : "system", action, detail);
    }

    public void system(String action, String detail) {
        record("system", action, detail);
    }
}
