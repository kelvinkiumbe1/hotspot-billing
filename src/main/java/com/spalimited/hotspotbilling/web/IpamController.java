package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.IpAssignment;
import com.spalimited.hotspotbilling.domain.IpSubnet;
import com.spalimited.hotspotbilling.repository.IpSubnetRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.ipam.IpamService;
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
 * Address planning. Guarded by NETWORK, the same permission as routers and
 * devices — it is the same person doing the same job.
 */
@RestController
@RequestMapping("/api/admin/ipam")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class IpamController {

    private final IpamService ipam;
    private final IpSubnetRepository subnets;
    private final SubscriberRepository subscribers;
    private final AuditService audit;

    @GetMapping
    public Map<String, Object> overview() {
        List<Map<String, Object>> all = ipam.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subnets", all);
        out.put("total", all.size());
        out.put("usable", all.stream().mapToLong(s -> (Long) s.get("usable")).sum());
        out.put("used", all.stream().mapToLong(s -> (Long) s.get("used")).sum());
        // Surfaced rather than waited for: running out of addresses is not a
        // gradual problem, it is the day an install cannot be finished.
        out.put("nearlyFull", ipam.nearlyFull(85));
        return out;
    }

    public record SubnetRequest(
            @NotBlank String name,
            @NotBlank String cidr,
            IpSubnet.Purpose purpose,
            String gateway,
            Integer vlanId,
            /*
             * Which interface this subnet lives on, needed to pin a static
             * customer's address to their equipment. Explicit rather than derived
             * from the VLAN id: a subnet might be on a bridge, a VLAN or a
             * physical port, and guessing produces a name most boards do not have.
             */
            String interfaceName,
            Long routerId,
            Long branchId,
            String description) {
    }

    @PostMapping("/subnets")
    public Map<String, Object> create(@Valid @RequestBody SubnetRequest request, Principal principal) {
        IpSubnet saved = ipam.create(IpSubnet.builder()
                .name(request.name().trim())
                .cidr(request.cidr().trim())
                .purpose(request.purpose() == null ? IpSubnet.Purpose.STATIC : request.purpose())
                .gateway(blankToNull(request.gateway()))
                .vlanId(request.vlanId())
                .interfaceName(blankToNull(request.interfaceName()))
                .routerId(request.routerId())
                .branchId(request.branchId())
                .description(blankToNull(request.description()))
                .build(), principal.getName());
        audit.record(principal, "ipam.subnet.create",
                "Added subnet " + saved.getName() + " (" + saved.getCidr() + ")");
        return ipam.describe(saved);
    }

    @PutMapping("/subnets/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @Valid @RequestBody SubnetRequest request,
                                      Principal principal) {
        IpSubnet subnet = subnets.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such subnet"));
        // The CIDR itself is not editable. Changing it would leave every
        // address already handed out sitting outside the block that owns it.
        subnet.setName(request.name().trim());
        subnet.setPurpose(request.purpose() == null ? subnet.getPurpose() : request.purpose());
        subnet.setVlanId(request.vlanId());
        subnet.setInterfaceName(blankToNull(request.interfaceName()));
        subnet.setRouterId(request.routerId());
        subnet.setBranchId(request.branchId());
        subnet.setDescription(blankToNull(request.description()));
        IpSubnet saved = subnets.save(subnet);
        audit.record(principal, "ipam.subnet.update", "Updated subnet " + saved.getName());
        return ipam.describe(saved);
    }

    @DeleteMapping("/subnets/{id}")
    public Map<String, Object> delete(@PathVariable Long id, Principal principal) {
        IpSubnet subnet = subnets.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such subnet"));
        ipam.delete(id);
        audit.record(principal, "ipam.subnet.delete",
                "Removed subnet " + subnet.getName() + " and every address in it");
        return Map.of("deleted", true);
    }

    /** Every address in a subnet that is not free, plus the next one that is. */
    @GetMapping("/subnets/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        IpSubnet subnet = subnets.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No such subnet"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IpAssignment a : ipam.assignmentsIn(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("address", a.getAddress());
            row.put("kind", a.getKind().name());
            row.put("subscriberId", a.getSubscriberId());
            row.put("subscriberName", a.getSubscriberId() == null ? null
                    : subscribers.findById(a.getSubscriberId())
                    .map(s -> s.getFullName()).orElse(null));
            row.put("deviceId", a.getDeviceId());
            row.put("hostname", a.getHostname());
            row.put("macAddress", a.getMacAddress());
            row.put("notes", a.getNotes());
            row.put("assignedAt", a.getAssignedAt());
            row.put("assignedBy", a.getAssignedBy());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>(ipam.describe(subnet));
        out.put("assignments", rows);
        out.put("nextFree", ipam.nextFree(id).orElse(null));
        return out;
    }

    public record AssignRequest(
            String address,
            IpAssignment.Kind kind,
            Long subscriberId,
            Long deviceId,
            String hostname,
            String macAddress,
            String notes) {
    }

    /** Takes an address. Leave the address blank to be given the next free one. */
    @PostMapping("/subnets/{id}/assign")
    public Map<String, Object> assign(@PathVariable Long id,
                                      @RequestBody AssignRequest request,
                                      Principal principal) {
        IpAssignment saved = ipam.assign(id, request.address(), request.kind(),
                request.subscriberId(), request.deviceId(), request.hostname(),
                request.macAddress(), request.notes(), principal.getName());
        audit.record(principal, "ipam.assign", "Assigned " + saved.getAddress()
                + (saved.getSubscriberId() != null ? " to subscriber " + saved.getSubscriberId() : ""));
        return Map.of("address", saved.getAddress(), "id", saved.getId(),
                "kind", saved.getKind().name());
    }

    @DeleteMapping("/assignments/{id}")
    public Map<String, Object> release(@PathVariable Long id, Principal principal) {
        ipam.release(id);
        audit.record(principal, "ipam.release", "Released address assignment " + id);
        return Map.of("released", true);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
