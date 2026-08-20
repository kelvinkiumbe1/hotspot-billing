package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.CpeDevice;
import com.spalimited.hotspotbilling.domain.CpeTask;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.domain.AcsSettings;
import com.spalimited.hotspotbilling.service.acs.AcsAuth;
import com.spalimited.hotspotbilling.service.acs.AcsService;
import com.spalimited.hotspotbilling.service.acs.ConnectionRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The routers in customers' houses, from the office.
 *
 * <p>Everything here queues rather than does. TR-069 only lets the ACS answer,
 * never ask, so "change the WiFi password" creates a task and either the device's
 * next Inform collects it or a connection request pokes the device into calling
 * now. The API says which happened rather than pretending the change is done.
 */
@RestController
@RequestMapping("/api/admin/cpe")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class CpeController {

    private final AcsService acs;
    private final ConnectionRequest connectionRequest;
    private final AuditService audit;
    private final AcsAuth acsAuth;

    // --- What a device must present to reach the ACS ---

    /**
     * The ACS credentials, without the password.
     *
     * <p>{@code configured} is what the page needs: until it is true the ACS
     * refuses every device, which is deliberate but has to be visible or it looks
     * like the network is broken.
     */
    @GetMapping("/acs-credentials")
    public Map<String, Object> acsCredentials() {
        AcsSettings s = acsAuth.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", s.getUsername() == null ? "" : s.getUsername());
        out.put("configured", acsAuth.configured());
        out.put("allowUnknown", s.isAllowUnknown());
        out.put("updatedAt", s.getUpdatedAt());
        out.put("updatedBy", s.getUpdatedBy());
        return out;
    }

    public record AcsCredentialsRequest(String username, String password, boolean allowUnknown) {
    }

    /** Sets them. A blank password leaves the existing one alone. */
    @PutMapping("/acs-credentials")
    public Map<String, Object> saveAcsCredentials(@RequestBody AcsCredentialsRequest body,
                                                 Principal principal) {
        String who = principal != null ? principal.getName() : "system";
        acsAuth.save(body.username(), body.password(), body.allowUnknown(), who);
        audit.record(who, "acs.credentials",
                "Updated the ACS credentials devices sign in with");
        return acsCredentials();
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CpeDevice device : acs.all()) {
            rows.add(describe(device));
        }
        return Map.of("devices", rows, "count", rows.size());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        CpeDevice device = acs.device(id)
                .orElseThrow(() -> new IllegalArgumentException("No such device"));
        Map<String, Object> out = new LinkedHashMap<>(describe(device));
        out.put("parameters", acs.parametersFor(id));
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (CpeTask task : acs.tasksFor(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.getId());
            row.put("kind", task.getKind());
            row.put("status", task.getStatus());
            row.put("fault", task.getFault());
            row.put("createdAt", task.getCreatedAt());
            row.put("completedAt", task.getCompletedAt());
            row.put("requestedBy", task.getRequestedBy());
            tasks.add(row);
        }
        out.put("tasks", tasks);
        return out;
    }

    private Map<String, Object> describe(CpeDevice device) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", device.getId());
        row.put("oui", device.getOui());
        row.put("serialNumber", device.getSerialNumber());
        row.put("manufacturer", device.getManufacturer());
        row.put("productClass", device.getProductClass());
        row.put("softwareVersion", device.getSoftwareVersion());
        row.put("dataModel", device.getDataModel());
        row.put("lastInformAt", device.getLastInformAt());
        row.put("lastEvent", device.getLastEvent());
        row.put("remoteAddress", device.getRemoteAddress());
        row.put("subscriberId", device.getSubscriberId());
        // Whether this box can be poked at all. A CPE behind carrier-grade NAT
        // reports a URL nothing outside can reach, and an operator needs to know
        // that before they promise a customer an instant change.
        row.put("reachable", device.getConnectionRequestUrl() != null
                && !device.getConnectionRequestUrl().isBlank());
        return row;
    }

    // ----------------------------------------------------------- the orders

    public record SettingsRequest(Map<String, String> settings, Map<String, String> raw,
                                  boolean now) {
    }

    /**
     * Changes settings on a device.
     *
     * <p>{@code settings} takes the names an operator thinks in — WIFI_SSID,
     * WIFI_PASSWORD — and the ACS turns each into whichever parameter path this
     * device's data model uses. {@code raw} takes exact paths, for anything the
     * named list has not heard of.
     *
     * <p>{@code now} pokes the device rather than waiting for its next Inform,
     * which is typically an hour away.
     */
    @PostMapping("/{id}/settings")
    public Map<String, Object> settings(@PathVariable Long id,
                                        @RequestBody SettingsRequest request,
                                        Principal principal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.settings() != null && !request.settings().isEmpty()) {
            payload.put("settings", request.settings());
        }
        if (request.raw() != null && !request.raw().isEmpty()) {
            payload.put("raw", request.raw());
        }
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("Nothing to change");
        }
        CpeTask task = acs.queue(id, CpeTask.Kind.SET_PARAMETERS, payload, principal.getName());
        audit.record(principal, "cpe.settings", "Queued a settings change on CPE " + id);
        return withPoke(task, id, request.now());
    }

    public record SimpleRequest(boolean now) {
    }

    @PostMapping("/{id}/reboot")
    public Map<String, Object> reboot(@PathVariable Long id,
                                      @RequestBody(required = false) SimpleRequest request,
                                      Principal principal) {
        CpeTask task = acs.queue(id, CpeTask.Kind.REBOOT, Map.of(), principal.getName());
        audit.record(principal, "cpe.reboot", "Queued a reboot on CPE " + id);
        return withPoke(task, id, request != null && request.now());
    }

    @PostMapping("/{id}/factory-reset")
    public Map<String, Object> factoryReset(@PathVariable Long id,
                                            @RequestBody(required = false) SimpleRequest request,
                                            Principal principal) {
        CpeTask task = acs.queue(id, CpeTask.Kind.FACTORY_RESET, Map.of(), principal.getName());
        // Loudly audited. This wipes a customer's router back to the factory,
        // including whatever WiFi name and password they were using.
        audit.record(principal, "cpe.factoryReset",
                "Queued a FACTORY RESET on CPE " + id + " — this wipes the customer's settings");
        return withPoke(task, id, request != null && request.now());
    }

    /** Reads values back, so an operator can see a WiFi name without guessing. */
    @PostMapping("/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable Long id,
                                       @RequestBody(required = false) SimpleRequest request,
                                       Principal principal) {
        CpeTask task = acs.queue(id, CpeTask.Kind.GET_PARAMETERS, Map.of(), principal.getName());
        return withPoke(task, id, request == null || request.now());
    }

    public record DownloadRequest(@NotBlank String url, Long size, String fileType, boolean now) {
    }

    @PostMapping("/{id}/firmware")
    public Map<String, Object> firmware(@PathVariable Long id,
                                        @RequestBody DownloadRequest request,
                                        Principal principal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", request.url());
        payload.put("size", request.size() == null ? 0 : request.size());
        if (request.fileType() != null && !request.fileType().isBlank()) {
            payload.put("fileType", request.fileType());
        }
        CpeTask task = acs.queue(id, CpeTask.Kind.DOWNLOAD, payload, principal.getName());
        audit.record(principal, "cpe.firmware", "Queued firmware for CPE " + id + ": "
                + request.url());
        return withPoke(task, id, request.now());
    }

    /**
     * Queues, then optionally pokes, then says honestly what happened.
     *
     * <p>The distinction matters to whoever is on the phone to a customer.
     * "Queued" means at the next check-in, up to an hour; "poked" means the device
     * was told to call in and should do so in seconds; "could not be reached"
     * means it is behind NAT and this will happen at its own pace.
     */
    private Map<String, Object> withPoke(CpeTask task, Long deviceId, boolean now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getId());
        out.put("status", task.getStatus());
        if (!now) {
            out.put("delivery", "queued");
            out.put("message", "It will be applied the next time the router checks in.");
            return out;
        }
        ConnectionRequest.Result poke = acs.device(deviceId)
                .map(connectionRequest::poke)
                .orElse(ConnectionRequest.Result.unreachable("No such device"));
        out.put("delivery", poke.reached() ? "poked" : "queued");
        out.put("message", poke.reached()
                ? "The router was asked to check in now; this should apply within seconds."
                : "Queued — the router could not be reached directly (" + poke.detail()
                  + "), so it will apply at its next check-in.");
        return out;
    }
}
