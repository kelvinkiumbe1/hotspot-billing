package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.OffpeakSettings;
import com.spalimited.hotspotbilling.service.AudienceService;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.OffPeakService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Off-peak offers: what the day actually looks like hour by hour, and the
 * discount that runs across the quiet part of it.
 */
@RestController
@RequestMapping("/api/admin/offpeak")
@RequiredArgsConstructor
public class OffPeakController {

    private final OffPeakService offPeak;
    private final AuditService audit;

    /** Settings, the shape of the day, and what a KES 100 pass would cost. */
    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> overview() {
        OffPeakService.DayShape shape = offPeak.analyse();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", offPeak.settings());
        out.put("hours", shape.hours());
        out.put("suggestedStart", shape.suggestedStart());
        out.put("suggestedEnd", shape.suggestedEnd());
        out.put("daysOfData", shape.daysOfData());
        out.put("note", shape.note());
        out.put("exampleHundred", offPeak.exampleAt(new BigDecimal("100")));
        out.put("audiences", AudienceService.SEGMENTS.stream()
                .map(s -> Map.of("key", s, "label", AudienceService.label(s)))
                .toList());
        return out;
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public OffpeakSettings save(@RequestBody OffpeakSettings body, Principal principal) {
        OffpeakSettings saved = offPeak.update(body);
        audit.record(principal, "settings.offpeak",
                "Updated off-peak offers (" + (saved.isEnabled() ? "on" : "off") + ")");
        return saved;
    }

    /**
     * Applies the schedule right now rather than waiting for the hour, so an
     * operator switching this on can see it take effect.
     */
    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> sync() {
        return offPeak.sync();
    }
}
