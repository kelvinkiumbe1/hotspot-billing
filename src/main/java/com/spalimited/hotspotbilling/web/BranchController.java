package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Branch;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.BranchRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Branch / franchise locations (HTTP Basic, ADMIN role). */
@RestController
@RequestMapping("/api/admin/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchRepository branches;
    private final RouterRepository routers;
    private final SubscriberRepository subscribers;
    private final AuditService audit;

    /** Branches with a small per-branch scorecard. */
    @GetMapping
    public List<Map<String, Object>> all() {
        return branches.findAllByOrderByNameAsc().stream().map(b -> {
            List<Router> branchRouters = routers.findAll().stream()
                    .filter(r -> b.getId().equals(r.getBranchId()))
                    .toList();
            List<Subscriber> branchSubs = subscribers.findAll().stream()
                    .filter(s -> b.getId().equals(s.getBranchId()))
                    .toList();
            BigDecimal mrr = branchSubs.stream()
                    .filter(s -> s.getStatus() == Subscriber.Status.ACTIVE)
                    .map(Subscriber::getMonthlyFee)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("name", b.getName());
            row.put("town", b.getTown());
            row.put("contactPhone", b.getContactPhone());
            row.put("active", b.isActive());
            row.put("routers", branchRouters.size());
            row.put("routersOnline", branchRouters.stream().filter(Router::isOnline).count());
            row.put("subscribers", branchSubs.size());
            row.put("monthlyRevenue", mrr);
            return row;
        }).toList();
    }

    public record BranchRequest(@NotBlank String name, String town, String contactPhone, Boolean active) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Branch create(@Valid @RequestBody BranchRequest request, Principal principal) {
        branches.findByName(request.name()).ifPresent(b -> {
            throw new IllegalArgumentException("A branch named '" + request.name() + "' already exists");
        });
        Branch saved = branches.save(Branch.builder()
                .name(request.name())
                .town(request.town())
                .contactPhone(request.contactPhone())
                .active(request.active() == null || request.active())
                .build());
        audit.record(principal, "branch.create", "Added branch " + saved.getName());
        return saved;
    }

    @PutMapping("/{id}")
    public Branch update(@PathVariable Long id, @Valid @RequestBody BranchRequest request, Principal principal) {
        Branch branch = branches.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown branch: " + id));
        branch.setName(request.name());
        branch.setTown(request.town());
        branch.setContactPhone(request.contactPhone());
        if (request.active() != null) {
            branch.setActive(request.active());
        }
        audit.record(principal, "branch.update", "Updated branch " + branch.getName());
        return branches.save(branch);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Principal principal) {
        branches.findById(id).ifPresent(b -> {
            routers.findAll().stream()
                    .filter(r -> b.getId().equals(r.getBranchId()))
                    .forEach(r -> { r.setBranchId(null); routers.save(r); });
            subscribers.findAll().stream()
                    .filter(s -> b.getId().equals(s.getBranchId()))
                    .forEach(s -> { s.setBranchId(null); subscribers.save(s); });
            audit.record(principal, "branch.delete", "Removed branch " + b.getName());
            branches.delete(b);
        });
    }
}
