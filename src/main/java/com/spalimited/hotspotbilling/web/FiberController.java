package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.FiberNode;
import com.spalimited.hotspotbilling.domain.FiberRoute;
import com.spalimited.hotspotbilling.repository.FiberNodeRepository;
import com.spalimited.hotspotbilling.repository.FiberRouteRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

/** The fibre plant: nodes, the cable runs between them, and their state. */
@RestController
@RequestMapping("/api/admin/fiber")
@RequiredArgsConstructor
public class FiberController {

    private final FiberNodeRepository nodes;
    private final FiberRouteRepository routes;
    private final SubscriberRepository subscribers;
    private final AuditService audit;

    /** Everything the map needs in one call, so it draws in a single pass. */
    @GetMapping("/plant")
    public Map<String, Object> plant() {
        Map<Long, String> subNames = new HashMap<>();
        subscribers.findAll().forEach(s -> subNames.put(s.getId(), s.getFullName()));

        List<Map<String, Object>> nodeRows = new ArrayList<>();
        for (FiberNode n : nodes.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId());
            row.put("name", n.getName());
            row.put("kind", n.getKind());
            row.put("status", n.getStatus());
            row.put("latitude", n.getLatitude());
            row.put("longitude", n.getLongitude());
            row.put("capacity", n.getCapacity());
            row.put("used", n.getUsed());
            row.put("free", n.getFree());
            row.put("parentId", n.getParentId());
            row.put("subscriberId", n.getSubscriberId());
            row.put("subscriberName", subNames.get(n.getSubscriberId()));
            row.put("routerId", n.getRouterId());
            row.put("address", n.getAddress());
            row.put("notes", n.getNotes());
            nodeRows.add(row);
        }

        List<Map<String, Object>> routeRows = new ArrayList<>();
        for (FiberRoute r : routes.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("name", r.getName());
            row.put("kind", r.getKind());
            row.put("status", r.getStatus());
            row.put("fromNodeId", r.getFromNodeId());
            row.put("toNodeId", r.getToNodeId());
            row.put("cores", r.getCores());
            row.put("lengthMeters", r.getLengthMeters());
            row.put("waypoints", parseWaypoints(r.getWaypoints()));
            row.put("notes", r.getNotes());
            routeRows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodeRows);
        out.put("routes", routeRows);
        out.put("summary", summary());
        return out;
    }

    /** Counts by kind and status, plus total cable length. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<FiberNode> all = nodes.findAll();
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (FiberNode.Kind kind : FiberNode.Kind.values()) {
            byKind.put(kind.name(), 0);
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (FiberNode.Status status : FiberNode.Status.values()) {
            byStatus.put(status.name(), 0);
        }
        int ports = 0;
        int usedPorts = 0;
        for (FiberNode n : all) {
            byKind.merge(n.getKind().name(), 1, Integer::sum);
            byStatus.merge(n.getStatus().name(), 1, Integer::sum);
            if (n.getCapacity() != null) {
                ports += n.getCapacity();
                usedPorts += n.getUsed() == null ? 0 : n.getUsed();
            }
        }

        List<FiberRoute> allRoutes = routes.findAll();
        int metres = allRoutes.stream()
                .filter(r -> r.getStatus() != FiberRoute.Status.DECOMMISSIONED)
                .mapToInt(r -> r.getLengthMeters() == null ? 0 : r.getLengthMeters())
                .sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", all.size());
        out.put("routes", allRoutes.size());
        out.put("byKind", byKind);
        out.put("byStatus", byStatus);
        out.put("ports", ports);
        out.put("usedPorts", usedPorts);
        out.put("freePorts", Math.max(0, ports - usedPorts));
        out.put("cableMetres", metres);
        out.put("faults", byStatus.getOrDefault("FAULT", 0)
                + (int) allRoutes.stream().filter(r -> r.getStatus() == FiberRoute.Status.FAULT).count());
        return out;
    }

    /** "lat,lng;lat,lng" to a list of pairs the map can plot directly. */
    private static List<List<Double>> parseWaypoints(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<List<Double>> out = new ArrayList<>();
        for (String pair : raw.split(";")) {
            String[] parts = pair.split(",");
            if (parts.length != 2) {
                continue;
            }
            try {
                out.add(List.of(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())));
            } catch (NumberFormatException skip) {
                // A malformed waypoint should not stop the whole route drawing.
            }
        }
        return out;
    }

    // --- Nodes ---

    public record NodeRequest(
            @NotBlank String name,
            @NotNull FiberNode.Kind kind,
            FiberNode.Status status,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Min(0) @Max(10000) Integer capacity,
            @Min(0) @Max(10000) Integer used,
            Long parentId,
            Long subscriberId,
            Long routerId,
            String address,
            String notes) {
    }

    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public FiberNode createNode(@Valid @RequestBody NodeRequest request, Principal principal) {
        validateCapacity(request.capacity(), request.used());
        FiberNode node = nodes.save(FiberNode.builder()
                .name(request.name())
                .kind(request.kind())
                .status(request.status() != null ? request.status() : FiberNode.Status.PLANNED)
                .latitude(request.latitude())
                .longitude(request.longitude())
                .capacity(request.capacity())
                .used(request.used())
                .parentId(request.parentId())
                .subscriberId(request.subscriberId())
                .routerId(request.routerId())
                .address(request.address())
                .notes(request.notes())
                .createdBy(principal.getName())
                .build());
        audit.record(principal, "fiber.node.create", "Added " + node.getKind() + " " + node.getName());
        return node;
    }

    @PutMapping("/nodes/{id}")
    public FiberNode updateNode(@PathVariable Long id, @Valid @RequestBody NodeRequest request, Principal principal) {
        validateCapacity(request.capacity(), request.used());
        FiberNode node = node(id);
        node.setName(request.name());
        node.setKind(request.kind());
        if (request.status() != null) {
            node.setStatus(request.status());
        }
        node.setLatitude(request.latitude());
        node.setLongitude(request.longitude());
        node.setCapacity(request.capacity());
        node.setUsed(request.used());
        node.setParentId(request.parentId());
        node.setSubscriberId(request.subscriberId());
        node.setRouterId(request.routerId());
        node.setAddress(request.address());
        node.setNotes(request.notes());
        audit.record(principal, "fiber.node.update", "Updated " + node.getName());
        return nodes.save(node);
    }

    public record NodeStatusRequest(@NotNull FiberNode.Status status, String notes) {
    }

    /** Flagging a fault is the common field action, so it has its own call. */
    @PatchMapping("/nodes/{id}/status")
    public FiberNode setNodeStatus(@PathVariable Long id, @Valid @RequestBody NodeStatusRequest request,
                                   Principal principal) {
        FiberNode node = node(id);
        node.setStatus(request.status());
        if (request.notes() != null && !request.notes().isBlank()) {
            node.setNotes(request.notes());
        }
        audit.record(principal, "fiber.node.status", node.getName() + " marked " + request.status());
        return nodes.save(node);
    }

    @DeleteMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNode(@PathVariable Long id, Principal principal) {
        FiberNode node = node(id);
        // A dangling route would draw a line to nowhere, so block the delete.
        long attached = routes.findAll().stream()
                .filter(r -> id.equals(r.getFromNodeId()) || id.equals(r.getToNodeId()))
                .count();
        if (attached > 0) {
            throw new IllegalStateException("Remove the " + attached + " route(s) joined to "
                    + node.getName() + " first");
        }
        audit.record(principal, "fiber.node.delete", "Removed " + node.getName());
        nodes.delete(node);
    }

    // --- Routes ---

    public record RouteRequest(
            String name,
            FiberRoute.Kind kind,
            FiberRoute.Status status,
            @NotNull Long fromNodeId,
            @NotNull Long toNodeId,
            @Min(1) @Max(1000) Integer cores,
            @Min(0) Integer lengthMeters,
            String waypoints,
            String notes) {
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public FiberRoute createRoute(@Valid @RequestBody RouteRequest request, Principal principal) {
        if (request.fromNodeId().equals(request.toNodeId())) {
            throw new IllegalArgumentException("A route needs two different ends");
        }
        FiberNode from = node(request.fromNodeId());
        FiberNode to = node(request.toNodeId());

        FiberRoute route = routes.save(FiberRoute.builder()
                .name(request.name() != null && !request.name().isBlank()
                        ? request.name() : from.getName() + " → " + to.getName())
                .kind(request.kind() != null ? request.kind() : FiberRoute.Kind.DISTRIBUTION)
                .status(request.status() != null ? request.status() : FiberRoute.Status.PLANNED)
                .fromNodeId(request.fromNodeId())
                .toNodeId(request.toNodeId())
                .cores(request.cores())
                .lengthMeters(request.lengthMeters())
                .waypoints(request.waypoints())
                .notes(request.notes())
                .createdBy(principal.getName())
                .build());
        audit.record(principal, "fiber.route.create", "Added route " + route.getName());
        return route;
    }

    public record RouteStatusRequest(@NotNull FiberRoute.Status status, String notes) {
    }

    @PatchMapping("/routes/{id}/status")
    public FiberRoute setRouteStatus(@PathVariable Long id, @Valid @RequestBody RouteStatusRequest request,
                                     Principal principal) {
        FiberRoute route = routes.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown route: " + id));
        route.setStatus(request.status());
        if (request.notes() != null && !request.notes().isBlank()) {
            route.setNotes(request.notes());
        }
        audit.record(principal, "fiber.route.status", route.getName() + " marked " + request.status());
        return routes.save(route);
    }

    @DeleteMapping("/routes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRoute(@PathVariable Long id, Principal principal) {
        routes.findById(id).ifPresent(route -> {
            audit.record(principal, "fiber.route.delete", "Removed route " + route.getName());
            routes.delete(route);
        });
    }

    private FiberNode node(Long id) {
        return nodes.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown fibre node: " + id));
    }

    private static void validateCapacity(Integer capacity, Integer used) {
        if (capacity != null && used != null && used > capacity) {
            throw new IllegalArgumentException("Used ports cannot exceed the capacity");
        }
    }
}
