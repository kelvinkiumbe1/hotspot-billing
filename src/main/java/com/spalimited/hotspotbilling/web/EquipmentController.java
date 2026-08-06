package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Equipment;
import com.spalimited.hotspotbilling.repository.EquipmentRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;

/** Stock of physical kit: what we own, where it is and who is holding it. */
@RestController
@RequestMapping("/api/admin/equipment")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class EquipmentController {

    private final EquipmentRepository equipment;
    private final TechnicianRepository technicians;
    private final SubscriberRepository subscribers;
    private final AuditService audit;

    @GetMapping
    public List<Map<String, Object>> all() {
        Map<Long, String> techNames = new HashMap<>();
        technicians.findAll().forEach(t -> techNames.put(t.getId(), t.getFullName()));
        Map<Long, String> subNames = new HashMap<>();
        subscribers.findAll().forEach(s -> subNames.put(s.getId(), s.getFullName()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Equipment item : equipment.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("name", item.getName());
            row.put("kind", item.getKind());
            row.put("model", item.getModel());
            row.put("serialNumber", item.getSerialNumber());
            row.put("macAddress", item.getMacAddress());
            row.put("status", item.getStatus());
            row.put("quantity", item.getQuantityOrOne());
            row.put("purchaseCost", item.getPurchaseCost());
            row.put("purchasedAt", item.getPurchasedAt());
            row.put("warrantyMonths", item.getWarrantyMonths());
            row.put("warrantyExpiry", item.getWarrantyExpiry());
            row.put("technicianId", item.getTechnicianId());
            row.put("technicianName", techNames.get(item.getTechnicianId()));
            row.put("subscriberId", item.getSubscriberId());
            row.put("subscriberName", subNames.get(item.getSubscriberId()));
            row.put("branchId", item.getBranchId());
            row.put("notes", item.getNotes());
            row.put("createdBy", item.getCreatedBy());
            row.put("createdAt", item.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** Counts per status plus the capital tied up, for the header cards. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Equipment> items = equipment.findAll();
        for (Equipment.Status status : Equipment.Status.values()) {
            out.put(status.name(), items.stream()
                    .filter(i -> i.getStatus() == status)
                    .mapToInt(Equipment::getQuantityOrOne)
                    .sum());
        }
        BigDecimal value = items.stream()
                .filter(i -> i.getStatus() != Equipment.Status.RETIRED && i.getPurchaseCost() != null)
                .map(i -> i.getPurchaseCost().multiply(BigDecimal.valueOf(i.getQuantityOrOne())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        out.put("assetValue", value);
        out.put("items", items.size());

        LocalDate soon = LocalDate.now().plusDays(60);
        out.put("warrantyExpiringSoon", items.stream()
                .filter(i -> i.getStatus() != Equipment.Status.RETIRED)
                .map(Equipment::getWarrantyExpiry)
                .filter(Objects::nonNull)
                .filter(d -> !d.isAfter(soon))
                .count());
        return out;
    }

    public record EquipmentRequest(
            @NotBlank String name,
            Equipment.Kind kind,
            String model,
            String serialNumber,
            String macAddress,
            @Min(1) @Max(100000) Integer quantity,
            BigDecimal purchaseCost,
            LocalDate purchasedAt,
            @Min(0) @Max(120) Integer warrantyMonths,
            Long branchId,
            String notes) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Equipment create(@Valid @RequestBody EquipmentRequest request, Principal principal) {
        String serial = blankToNull(request.serialNumber());
        if (serial != null) {
            equipment.findBySerialNumber(serial).ifPresent(existing -> {
                throw new IllegalArgumentException("Serial number already logged: " + serial);
            });
        }
        Equipment saved = equipment.save(Equipment.builder()
                .name(request.name())
                .kind(request.kind() != null ? request.kind() : Equipment.Kind.OTHER)
                .model(blankToNull(request.model()))
                .serialNumber(serial)
                .macAddress(blankToNull(request.macAddress()))
                .quantity(request.quantity() != null ? request.quantity() : 1)
                .purchaseCost(request.purchaseCost())
                .purchasedAt(request.purchasedAt())
                .warrantyMonths(request.warrantyMonths())
                .branchId(request.branchId())
                .notes(blankToNull(request.notes()))
                .createdBy(principal.getName())
                .build());
        audit.record(principal, "equipment.create", "Logged " + saved.getName()
                + (serial != null ? " (" + serial + ")" : ""));
        return saved;
    }

    public record AssignRequest(
            @NotNull Equipment.Status status,
            Long technicianId,
            Long subscriberId,
            String notes) {
    }

    /**
     * Moves an item through the stock lifecycle. The holder fields are kept
     * consistent with the status so an item is never both in a store and
     * installed at a customer's place.
     */
    @PatchMapping("/{id}/status")
    public Equipment setStatus(@PathVariable Long id, @Valid @RequestBody AssignRequest request, Principal principal) {
        Equipment item = get(id);
        item.setStatus(request.status());
        switch (request.status()) {
            case WITH_TECHNICIAN -> {
                if (request.technicianId() == null) {
                    throw new IllegalArgumentException("Choose the technician taking this item");
                }
                item.setTechnicianId(request.technicianId());
                item.setSubscriberId(null);
            }
            case DEPLOYED -> {
                if (request.subscriberId() == null) {
                    throw new IllegalArgumentException("Choose the subscriber this item is installed for");
                }
                item.setSubscriberId(request.subscriberId());
            }
            case IN_STOCK -> {
                item.setTechnicianId(null);
                item.setSubscriberId(null);
            }
            case FAULTY, RETIRED -> {
                // Keep the last holder on record for fault history.
            }
        }
        if (request.notes() != null && !request.notes().isBlank()) {
            item.setNotes(request.notes());
        }
        audit.record(principal, "equipment.status", item.getName() + " marked " + request.status());
        return equipment.save(item);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Principal principal) {
        equipment.findById(id).ifPresent(item -> {
            audit.record(principal, "equipment.delete", "Removed " + item.getName() + " from stock");
            equipment.delete(item);
        });
    }

    private Equipment get(Long id) {
        return equipment.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown equipment: " + id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
