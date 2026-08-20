package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.RouterMove;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.service.MikrotikService;
import com.spalimited.hotspotbilling.service.RouterFleetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The router fleet: what is on each box, moving customers, and looking inside.
 *
 * <p>Every read here goes to live hardware, so each is one router at a time and
 * on request. Nothing on this controller is polled.
 */
@RestController
@RequestMapping("/api/admin/fleet")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class RouterFleetController {

    private final RouterFleetService fleet;
    private final MikrotikService mikrotikService;
    private final RouterRepository routers;

    @GetMapping
    public Map<String, Object> overview() {
        List<Map<String, Object>> moves = new ArrayList<>();
        for (RouterMove m : fleet.recentMoves()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("kind", m.getKind());
            row.put("fromRouterId", m.getFromRouterId());
            row.put("toRouterId", m.getToRouterId());
            row.put("startedAt", m.getStartedAt());
            row.put("moved", m.getMovedCount());
            row.put("failed", m.getFailedCount());
            row.put("detail", m.getDetail());
            row.put("startedBy", m.getStartedBy());
            moves.add(row);
        }
        return Map.of("routers", fleet.fleet(), "moves", moves,
                "roamingEnabled", mikrotikService.roamingOn());
    }

    public record TransferRequest(@NotEmpty List<Long> subscriberIds, @NotNull Long toRouterId) {
    }

    @PostMapping("/transfer")
    public Map<String, Object> transfer(@Valid @RequestBody TransferRequest request,
                                        Principal principal) {
        RouterFleetService.Outcome out = fleet.transfer(request.subscriberIds(),
                request.toRouterId(), who(principal));
        return render(out);
    }

    public record ReplaceRequest(@NotNull Long fromRouterId, @NotNull Long toRouterId,
                                 boolean copySettings) {
    }

    @PostMapping("/replace")
    public Map<String, Object> replace(@Valid @RequestBody ReplaceRequest request,
                                       Principal principal) {
        RouterFleetService.Outcome out = fleet.replace(request.fromRouterId(),
                request.toRouterId(), request.copySettings(), who(principal));
        return render(out);
    }

    // --- Looking inside one router ---

    /**
     * The router's own log.
     *
     * <p>When a customer cannot get online the answer is very often already
     * written here -- a failed PPPoE login naming the wrong password, a DHCP pool
     * with nothing left, an interface flapping. Today that means somebody opening
     * WinBox, which means somebody who has WinBox and the password, which means it
     * does not happen.
     */
    @GetMapping("/{routerId}/logs")
    public Map<String, Object> logs(@PathVariable Long routerId,
                                    @RequestParam(required = false) @Size(max = 60) String topics,
                                    @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        return Map.of("logs", mikrotikService.logs(router(routerId), topics, limit));
    }

    @GetMapping("/{routerId}/wireless")
    public Map<String, Object> wireless(@PathVariable Long routerId) {
        return Map.of("wireless", mikrotikService.wireless(router(routerId)));
    }

    public record WirelessRequest(@NotNull String api, @NotNull String name,
                                  @Size(max = 64) String ssid,
                                  @Size(min = 8, max = 64) String password) {
    }

    /**
     * Renames a wireless network, and optionally changes its password.
     *
     * <p>The reply names the security profile the password landed on, because on
     * classic RouterOS a profile is often shared by several interfaces and
     * changing it changes all of them. Reporting which one was touched is the
     * difference between a change an operator understands and a mystery outage on
     * a second SSID.
     */
    @PostMapping("/{routerId}/wireless")
    public Map<String, Object> setWireless(@PathVariable Long routerId,
                                           @Valid @RequestBody WirelessRequest request) {
        String profile = mikrotikService.setWireless(router(routerId), request.api(),
                request.name(), request.ssid(), request.password());
        String message = "Saved.";
        if (profile != null) {
            message = "Saved. The password was set on " + profile
                    + (profile.equals("the interface") ? "."
                            : ", which any other network using that profile shares.")
                    + " Devices already connected stay on until they reconnect.";
        }
        return Map.of("ok", true, "profile", profile == null ? "" : profile, "message", message);
    }

    @GetMapping("/{routerId}/bridges")
    public Map<String, Object> bridges(@PathVariable Long routerId) {
        return mikrotikService.bridges(router(routerId));
    }

    @GetMapping("/{routerId}/interfaces")
    public Map<String, Object> interfaces(@PathVariable Long routerId) {
        return Map.of("interfaces", mikrotikService.interfaces(router(routerId)));
    }

    private Router router(Long id) {
        return routers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such router"));
    }

    private static Map<String, Object> render(RouterFleetService.Outcome out) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", out.failed() == 0);
        body.put("moveId", out.moveId());
        body.put("moved", out.moved());
        body.put("failed", out.failed());
        body.put("problems", out.problems());
        body.put("message", out.message());
        return body;
    }

    private static String who(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
