package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.olt.OltProvisioningService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authorising ONUs, with the safety catch left on.
 *
 * <p>Every write here is two calls on purpose. {@code /preview} builds the exact
 * commands and sends nothing; {@code /apply} takes those same commands back and
 * runs them. An operator therefore cannot provision without having been shown
 * what will be typed, and what is typed cannot drift from what was shown, because
 * the second call does not rebuild it.
 *
 * <p>That is not ceremony. This is the only part of the system where a mistake
 * darkens streets rather than failing a payment, and none of these commands has
 * ever been run against real hardware.
 */
@RestController
@RequestMapping("/api/admin/olt")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class OltProvisioningController {

    private final OltProvisioningService provisioning;

    /** ONUs the OLT can see and nobody has authorised. Read-only. */
    @GetMapping("/{id}/unregistered")
    public Map<String, Object> unregistered(@PathVariable Long id) {
        List<OltProvisioningService.Unregistered> found = provisioning.unregistered(id);
        List<Map<String, Object>> rows = found.stream().map(u -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serial", u.serial());
            row.put("frame", u.frame());
            row.put("slot", u.slot());
            row.put("port", u.port());
            // The OLT's own line, so an operator can see what was read and judge
            // whether the parse got it right. This is guesswork on unverified
            // output and saying so beats hiding it.
            row.put("raw", u.raw());
            return row;
        }).toList();
        return Map.of("onus", rows, "count", rows.size());
    }

    public record PlacementRequest(
            @NotBlank String serial,
            String frame, String slot, String port,
            String onuId, String name,
            String lineProfile, String srvProfile) {

        OltProvisioningService.Placement toPlacement() {
            return new OltProvisioningService.Placement(serial, frame, slot, port,
                    onuId, name, lineProfile, srvProfile);
        }
    }

    /** What authorising this ONU would type. Types nothing. */
    @PostMapping("/{id}/preview/{action}")
    public Map<String, Object> preview(@PathVariable Long id, @PathVariable String action,
                                       @RequestBody PlacementRequest request) {
        OltProvisioningService.Plan plan = planFor(id, action, request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("possible", plan.possible());
        out.put("reason", plan.reason());
        out.put("commands", plan.commands());
        out.put("warning", "These have not been run against this model of OLT before. "
                + "Read them, and check them against your own box, before applying.");
        return out;
    }

    public record ApplyRequest(String action, PlacementRequest placement,
                               List<String> commands) {
    }

    /**
     * Runs the commands that were previewed.
     *
     * <p>The commands come back from the client rather than being rebuilt, and are
     * checked against a fresh preview before anything is sent. That gives both
     * halves of what is wanted: what runs is what the operator read, and a client
     * cannot post arbitrary commands to an OLT through this endpoint.
     */
    @PostMapping("/{id}/apply")
    public Map<String, Object> apply(@PathVariable Long id, @RequestBody ApplyRequest request,
                                     Principal principal) {
        OltProvisioningService.Plan plan = planFor(id, request.action(), request.placement());
        if (!plan.possible()) {
            return Map.of("ok", false, "detail", plan.reason());
        }
        if (request.commands() != null && !request.commands().equals(plan.commands())) {
            // Either the operator is looking at a stale preview, or something is
            // trying to post its own commands. Both end here: this endpoint runs
            // what the service builds and nothing else.
            return Map.of("ok", false,
                    "detail", "What you were shown is no longer what this would do. "
                            + "Preview it again and check the commands.");
        }
        OltProvisioningService.Outcome outcome =
                provisioning.apply(id, plan, request.action(), principal.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", outcome.ok());
        out.put("detail", outcome.detail());
        // The whole conversation, sent and received. On a rail with no sandbox
        // this is the only way an operator can tell a wrong command from an
        // unreachable box.
        out.put("transcript", outcome.transcript());
        return out;
    }

    private OltProvisioningService.Plan planFor(Long id, String action,
                                                PlacementRequest request) {
        if (request == null) {
            return new OltProvisioningService.Plan(false, "Which ONU?", List.of());
        }
        OltProvisioningService.Placement placement = request.toPlacement();
        return switch (action == null ? "" : action.toLowerCase(java.util.Locale.ROOT)) {
            case "authorise", "authorize" -> provisioning.previewAuthorise(id, placement);
            case "deauthorise", "deauthorize" -> provisioning.previewDeauthorise(id, placement);
            case "reboot" -> provisioning.previewReboot(id, placement);
            default -> new OltProvisioningService.Plan(false,
                    "Unknown action. Use authorise, deauthorise or reboot.", List.of());
        };
    }
}
