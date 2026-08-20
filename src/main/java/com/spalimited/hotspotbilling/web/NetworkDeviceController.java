package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.DeviceInterface;
import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.repository.DeviceInterfaceRepository;
import com.spalimited.hotspotbilling.repository.NetworkDeviceRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.snmp.DeviceMonitorService;
import jakarta.validation.Valid;
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
 * The switches, antennas, ONTs and UPSes behind the routers.
 *
 * <p>Guarded by NETWORK, the same permission as routers: this is the same job
 * done by the same person, and a separate permission would only mean a network
 * engineer who can see half their own network.
 */
@RestController
@RequestMapping("/api/admin/devices")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class NetworkDeviceController {

    private final NetworkDeviceRepository devices;
    private final DeviceInterfaceRepository interfaces;
    private final DeviceMonitorService monitor;
    private final AuditService audit;
    private final com.spalimited.hotspotbilling.service.snmp.OntOpticalService optical;

    public record DeviceRequest(
            @NotBlank String name,
            NetworkDevice.Kind kind,
            @NotBlank String host,
            Integer port,
            String location,
            Long branchId,
            Boolean enabled,
            NetworkDevice.Version snmpVersion,
            String community,
            String securityName,
            NetworkDevice.AuthProtocol authProtocol,
            String authPassphrase,
            NetworkDevice.PrivProtocol privProtocol,
            String privPassphrase,
            String notes,
            // OLT only, and all optional. Null means "use the vendor preset",
            // which is why none of these is validated into existence: an operator
            // adding a switch never sees them.
            NetworkDevice.OltVendor oltVendor,
            String onuSerialOid,
            String onuRxPowerOid,
            String onuTxPowerOid,
            String onuStatusOid,
            com.spalimited.hotspotbilling.service.snmp.OpticalPower.Unit onuPowerUnit,
            Double onuPowerScale) {
    }

    @GetMapping
    public Map<String, Object> list() {
        List<NetworkDevice> all = devices.findAllByOrderByNameAsc();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NetworkDevice d : all) {
            Map<String, Object> row = summary(d);
            List<DeviceInterface> ports = interfaces.findByDeviceIdOrderByIfIndexAsc(d.getId());
            row.put("portsTotal", ports.size());
            row.put("portsUp", ports.stream().filter(DeviceInterface::isOperUp).count());
            row.put("portsWatched", ports.stream().filter(DeviceInterface::isMonitored).count());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", rows);
        out.put("total", all.size());
        out.put("offline", all.stream().filter(d -> d.isEnabled() && !d.isOnline()).count());
        // Said plainly rather than left for someone to work out from a version
        // dropdown: a v2c community string is a password sent in clear text on
        // every poll, five minutes apart, forever.
        out.put("inClear", all.stream().filter(d -> d.isEnabled() && d.isCredentialInClear()).count());
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        NetworkDevice device = devices.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such device"));
        Map<String, Object> out = summary(device);
        out.put("sysDescr", device.getSysDescr());
        out.put("sysContact", device.getSysContact());
        out.put("notes", device.getNotes());
        out.put("ports", interfaces.findByDeviceIdOrderByIfIndexAsc(id).stream()
                .map(NetworkDeviceController::port).toList());
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody DeviceRequest request, Principal principal) {
        devices.findByName(request.name().trim()).ifPresent(existing -> {
            throw new IllegalArgumentException("A device called '" + request.name() + "' already exists");
        });
        NetworkDevice device = NetworkDevice.builder().name(request.name().trim()).build();
        apply(device, request);
        NetworkDevice saved = devices.save(device);
        audit.record(principal, "device.create", "Added device " + saved.getName()
                + " (" + saved.getHost() + ")");
        return summary(saved);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody DeviceRequest request,
                                      Principal principal) {
        NetworkDevice device = devices.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such device"));
        device.setName(request.name().trim());
        apply(device, request);
        NetworkDevice saved = devices.save(device);
        audit.record(principal, "device.update", "Updated device " + saved.getName());
        return summary(saved);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        NetworkDevice device = devices.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such device"));
        interfaces.deleteByDeviceId(id);
        devices.delete(device);
        audit.record(principal, "device.delete", "Removed device " + device.getName());
        return Map.of("deleted", true);
    }

    /**
     * Polls right now instead of waiting five minutes. This is what turns
     * adding a device from a guess into something you can confirm while you
     * still have the switch's console open.
     */
    @PostMapping("/{id}/check")
    public Map<String, Object> check(@PathVariable Long id) {
        DeviceMonitorService.PollResult result = monitor.poll(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", result.online());
        out.put("portsSeen", result.portsSeen());
        out.put("portsUp", result.portsUp());
        out.put("message", result.online()
                ? "Answered — " + result.portsUp() + " of " + result.portsSeen() + " ports up"
                : result.error());
        return out;
    }

    // --- Fibre: the light on every ONU hanging off an OLT ---

    /**
     * Every ONU on one OLT, worst light first.
     *
     * <p>Worst first because that is the order somebody works in. A list sorted
     * by serial number is a list nobody reads.
     */
    @GetMapping("/{id}/onus")
    public Map<String, Object> onus(@PathVariable Long id) {
        List<Map<String, Object>> rows = optical.forOlt(id).stream().map(this::describeOnu).toList();
        return Map.of("onus", rows, "count", rows.size());
    }

    /** Reads one OLT now, for the operator who has just fixed an OID. */
    @PostMapping("/{id}/onus/check")
    public Map<String, Object> checkOnus(@PathVariable Long id) {
        var result = optical.poll(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("polled", result.polled());
        out.put("onusSeen", result.onusSeen());
        out.put("alerted", result.alerted());
        out.put("error", result.error());
        return out;
    }

    /** Everything not currently healthy, across every OLT. The fibre worklist. */
    @GetMapping("/onus/attention")
    public Map<String, Object> attention() {
        List<Map<String, Object>> rows =
                optical.attentionNeeded().stream().map(this::describeOnu).toList();
        return Map.of("onus", rows, "count", rows.size());
    }

    /** What a vendor's preset actually is, so the form can show it as a placeholder. */
    @GetMapping("/olt-presets")
    public Map<String, Object> presets() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NetworkDevice.OltVendor vendor : NetworkDevice.OltVendor.values()) {
            var columns = com.spalimited.hotspotbilling.service.snmp.OltProfile.preset(vendor);
            if (columns == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serial", columns.serial());
            row.put("rxPower", columns.rxPower());
            row.put("txPower", columns.txPower());
            row.put("status", columns.status());
            row.put("unit", columns.unit());
            row.put("scale", columns.scale());
            out.put(vendor.name(), row);
        }
        return Map.of("presets", out);
    }

    private Map<String, Object> describeOnu(com.spalimited.hotspotbilling.domain.OntReading r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("oltDeviceId", r.getOltDeviceId());
        row.put("serial", r.getSerial());
        row.put("description", r.getDescription());
        row.put("tableIndex", r.getTableIndex());
        row.put("status", r.getStatus());
        row.put("rxDbm", r.getRxDbm());
        row.put("txDbm", r.getTxDbm());
        row.put("previousRxDbm", r.getPreviousRxDbm());
        row.put("health", r.getHealth());
        // The sentence, not just the band. "past the budget, expect drops" is
        // what gets a van sent; "BAD" is what gets ignored.
        row.put("verdict", com.spalimited.hotspotbilling.service.snmp.OntOpticalService
                .plainly(r.getHealth()));
        row.put("subscriberId", r.getSubscriberId());
        row.put("lastSeenAt", r.getLastSeenAt());
        return row;
    }

    public record PortWatchRequest(boolean monitored) {
    }

    /**
     * Marks a port worth being woken up for. Deliberately opt-in: alerting on
     * every unused access port is how an operator learns to ignore alerts.
     */
    @PutMapping("/ports/{portId}/watch")
    public Map<String, Object> watch(@PathVariable Long portId, @RequestBody PortWatchRequest request,
                                     Principal principal) {
        DeviceInterface row = interfaces.findById(portId).orElseThrow(() ->
                new IllegalArgumentException("No such port"));
        row.setMonitored(request.monitored());
        interfaces.save(row);
        audit.record(principal, "device.port.watch",
                (request.monitored() ? "Watching " : "Stopped watching ") + row.getLabel());
        return port(row);
    }

    private void apply(NetworkDevice device, DeviceRequest request) {
        device.setKind(request.kind() == null ? NetworkDevice.Kind.OTHER : request.kind());
        device.setHost(request.host().trim());
        device.setPort(request.port() == null || request.port() <= 0 ? 161 : request.port());
        device.setLocation(blankToNull(request.location()));
        // Straight through, including null. Null is a real choice here -- it means
        // "use the vendor preset" -- so these deliberately do not follow the
        // keepIfBlank rule the secrets below use.
        device.setOltVendor(request.oltVendor());
        device.setOnuSerialOid(blankToNull(request.onuSerialOid()));
        device.setOnuRxPowerOid(blankToNull(request.onuRxPowerOid()));
        device.setOnuTxPowerOid(blankToNull(request.onuTxPowerOid()));
        device.setOnuStatusOid(blankToNull(request.onuStatusOid()));
        device.setOnuPowerUnit(request.onuPowerUnit());
        device.setOnuPowerScale(request.onuPowerScale());
        device.setBranchId(request.branchId());
        device.setEnabled(request.enabled() == null || request.enabled());
        device.setSnmpVersion(request.snmpVersion() == null
                ? NetworkDevice.Version.V2C : request.snmpVersion());
        device.setSecurityName(blankToNull(request.securityName()));
        device.setAuthProtocol(request.authProtocol());
        device.setPrivProtocol(request.privProtocol());
        device.setNotes(blankToNull(request.notes()));
        // Secrets are never sent back to the browser, so a blank one here means
        // "unchanged" rather than "cleared" — otherwise editing the location
        // would silently wipe the credential and the device would go dark.
        keepIfBlank(request.community(), device::getCommunity, device::setCommunity);
        keepIfBlank(request.authPassphrase(), device::getAuthPassphrase, device::setAuthPassphrase);
        keepIfBlank(request.privPassphrase(), device::getPrivPassphrase, device::setPrivPassphrase);
    }

    private static void keepIfBlank(String incoming, java.util.function.Supplier<String> current,
                                    java.util.function.Consumer<String> setter) {
        if (incoming != null && !incoming.isBlank() && !incoming.startsWith("••••")) {
            setter.accept(incoming.trim());
        } else if (current.get() == null) {
            setter.accept(null);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> summary(NetworkDevice d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", d.getId());
        row.put("name", d.getName());
        row.put("kind", d.getKind().name());
        row.put("host", d.getHost());
        row.put("port", d.getPort());
        row.put("location", d.getLocation());
        row.put("branchId", d.getBranchId());
        row.put("enabled", d.isEnabled());
        row.put("snmpVersion", d.getSnmpVersion().name());
        row.put("securityName", d.getSecurityName());
        row.put("authProtocol", d.getAuthProtocol() == null ? null : d.getAuthProtocol().name());
        row.put("privProtocol", d.getPrivProtocol() == null ? null : d.getPrivProtocol().name());
        row.put("configured", d.isConfigured());
        row.put("credentialInClear", d.isCredentialInClear());
        // Enough to show a credential is stored, never enough to use it.
        row.put("hasCommunity", d.getCommunity() != null && !d.getCommunity().isBlank());
        row.put("hasAuthPassphrase", d.getAuthPassphrase() != null && !d.getAuthPassphrase().isBlank());
        row.put("hasPrivPassphrase", d.getPrivPassphrase() != null && !d.getPrivPassphrase().isBlank());
        row.put("online", d.isOnline());
        row.put("lastSeenAt", d.getLastSeenAt());
        row.put("lastCheckedAt", d.getLastCheckedAt());
        row.put("lastError", d.getLastError());
        row.put("sysName", d.getSysName());
        row.put("sysLocation", d.getSysLocation());
        row.put("uptimeSeconds", d.getUptimeSeconds());
        row.put("lastRebootAt", d.getLastRebootAt());
        return row;
    }

    private static Map<String, Object> port(DeviceInterface p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("ifIndex", p.getIfIndex());
        row.put("label", p.getLabel());
        row.put("ifName", p.getIfName());
        row.put("ifAlias", p.getIfAlias());
        row.put("adminUp", p.isAdminUp());
        row.put("operUp", p.isOperUp());
        row.put("speedBps", p.getSpeedBps());
        row.put("inBps", p.getInBps());
        row.put("outBps", p.getOutBps());
        row.put("utilisation", p.getUtilisationPercent());
        row.put("inErrors", p.getInErrorsDelta());
        row.put("outErrors", p.getOutErrorsDelta());
        row.put("monitored", p.isMonitored());
        row.put("lastChangeAt", p.getLastChangeAt());
        return row;
    }
}
