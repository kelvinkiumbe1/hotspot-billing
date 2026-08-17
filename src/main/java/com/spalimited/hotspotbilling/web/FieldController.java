package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.FieldSettings;
import com.spalimited.hotspotbilling.service.FieldBotService;
import com.spalimited.hotspotbilling.service.FieldOpsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Field work automation: the settings behind the technicians' WhatsApp
 * assistant and the sweeps that chase quiet jobs, plus a preview so an
 * operator can try the technician conversation without a handset.
 */
@RestController
@RequestMapping("/api/admin/field")
@RequiredArgsConstructor
public class FieldController {

    private final FieldOpsService fieldOps;
    private final FieldBotService fieldBot;

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public FieldSettings getSettings() {
        return fieldOps.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public FieldSettings saveSettings(@RequestBody FieldSettings in) {
        return fieldOps.update(in);
    }

    /** Runs the chase now rather than waiting for the next sweep. */
    @PostMapping("/sweep")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, Object> sweep() {
        return fieldOps.runSweep();
    }

    public record Sim(String phone, String text) {
    }

    /**
     * Preview of the technician conversation. Returns a plain explanation
     * rather than a reply when the number is not a technician's — that is the
     * commonest reason this looks broken, and it is worth saying out loud.
     */
    @PostMapping("/simulate")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, String> simulate(@RequestBody Sim sim) {
        String reply = fieldBot.reply(sim.phone(), sim.text());
        if (reply == null) {
            return Map.of("reply", "",
                    "note", "No active technician has that phone number, so this message would be "
                            + "answered by the customer bot instead. Set the number on the technician's "
                            + "record under Team.");
        }
        return Map.of("reply", reply);
    }
}
