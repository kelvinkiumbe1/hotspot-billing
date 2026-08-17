package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CapacitySettings;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.CapacityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Capacity planning: each site's busy hour against what its link can carry,
 * where the growth is heading, and who is using the most of it.
 */
@RestController
@RequestMapping("/api/admin/capacity")
@RequiredArgsConstructor
public class CapacityController {

    private final CapacityService capacity;
    private final AuditService audit;

    @GetMapping
    // Capacity sits on the line between the network and the books, and the
    // screen that shows it also sets it — so either permission opens both.
    @PreAuthorize("hasAnyAuthority('NETWORK','SETTINGS')")
    public Map<String, Object> overview() {
        CapacityService.Outlook outlook = capacity.outlook();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", capacity.settings());
        out.put("sites", outlook.sites());
        out.put("heaviest", outlook.heaviest());
        out.put("daysOfData", outlook.daysOfData());
        out.put("note", outlook.note());
        return out;
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public CapacitySettings save(@RequestBody CapacitySettings body, Principal principal) {
        CapacitySettings saved = capacity.update(body);
        audit.record(principal, "settings.capacity",
                "Updated capacity planning (" + (saved.isEnabled() ? "on" : "off") + ")");
        return saved;
    }

    public record CapacityRequest(Integer capacityMbps) {
    }

    /** What this site's link can carry — the one figure nothing can measure. */
    @PutMapping("/routers/{id}")
    // Capacity sits on the line between the network and the books, and the
    // screen that shows it also sets it — so either permission opens both.
    @PreAuthorize("hasAnyAuthority('NETWORK','SETTINGS')")
    public Router setCapacity(@PathVariable Long id, @RequestBody CapacityRequest body, Principal principal) {
        Router router = capacity.setCapacity(id, body.capacityMbps());
        audit.record(principal, "router.capacity",
                "Set " + router.getName() + " link capacity to "
                        + (router.getCapacityMbps() == null ? "unknown" : router.getCapacityMbps() + " Mbps"));
        return router;
    }
}
