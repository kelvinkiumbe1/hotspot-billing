package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

/**
 * Office logins. Only a role holding the STAFF permission — Owner — may
 * touch these, which is what stops a manager promoting themselves.
 */
@RestController
@RequestMapping("/api/admin/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffUserRepository staff;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    /**
     * Who am I and what may I do — the UI hides what the server would refuse.
     * Deliberately unguarded beyond being signed in: every role has to be
     * able to ask this, and it only ever reports the caller's own identity.
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        Set<String> authorities = new LinkedHashSet<>();
        authentication.getAuthorities().forEach(a -> authorities.add(a.getAuthority()));

        StaffUser member = staff.findByUsernameAndActiveTrue(authentication.getName()).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", authentication.getName());
        out.put("fullName", member != null ? member.getFullName() : authentication.getName());
        out.put("role", member != null ? member.getRole().name() : roleFrom(authorities));
        // Match against the permissions we define rather than "anything without
        // a ROLE_ prefix" — Spring Security adds its own authorities (such as
        // FACTOR_PASSWORD) that are not ours to hand to the UI.
        Set<String> known = new LinkedHashSet<>();
        for (StaffUser.Role role : StaffUser.Role.values()) {
            known.addAll(StaffUser.permissions(role));
        }
        out.put("permissions", authorities.stream()
                .filter(known::contains)
                .sorted()
                .toList());
        // True while signed in through application.properties rather than a
        // real staff row, so the UI can nudge toward creating one.
        out.put("breakGlass", member == null);
        return out;
    }

    private static String roleFrom(Set<String> authorities) {
        for (StaffUser.Role role : StaffUser.Role.values()) {
            if (authorities.contains("ROLE_" + role.name())) {
                return role.name();
            }
        }
        return "OWNER";
    }

    @GetMapping
    @PreAuthorize("hasAuthority('STAFF')")
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StaffUser member : staff.findAllByOrderByCreatedAtAsc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", member.getId());
            row.put("username", member.getUsername());
            row.put("fullName", member.getFullName());
            row.put("phoneNumber", member.getPhoneNumber());
            row.put("email", member.getEmail());
            row.put("role", member.getRole());
            row.put("permissions", member.getPermissions().stream().sorted().toList());
            row.put("active", member.isActive());
            row.put("seeded", member.isSeeded());
            row.put("createdBy", member.getCreatedBy());
            row.put("lastLoginAt", member.getLastLoginAt());
            row.put("createdAt", member.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** What each role can reach, so the UI can explain the choice. */
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('STAFF')")
    public List<Map<String, Object>> roles() {
        return Arrays.stream(StaffUser.Role.values())
                .map(role -> Map.<String, Object>of(
                        "key", role.name(),
                        "permissions", StaffUser.permissions(role).stream().sorted().toList()))
                .toList();
    }

    public record StaffRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9._-]{3,20}",
                    message = "Username must be 3-20 lowercase letters, digits, dot, dash or underscore")
            String username,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
            @NotBlank String fullName,
            String phoneNumber,
            @Email(message = "That does not look like an email address") String email,
            @NotNull StaffUser.Role role) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody StaffRequest request, Principal principal) {
        String username = request.username().trim().toLowerCase();
        if (staff.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("That username is already taken");
        }
        StaffUser member = staff.save(StaffUser.builder()
                .username(username)
                .passwordHash(encoder.encode(request.password()))
                .fullName(request.fullName())
                .phoneNumber(request.phoneNumber())
                .email(request.email())
                .role(request.role())
                .createdBy(principal.getName())
                .build());
        audit.record(principal, "staff.create",
                "Added " + member.getFullName() + " as " + member.getRole());
        return Map.of("id", member.getId(), "username", member.getUsername(), "role", member.getRole());
    }

    public record RoleRequest(@NotNull StaffUser.Role role) {
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAuthority('STAFF')")
    public Map<String, Object> setRole(@PathVariable Long id, @Valid @RequestBody RoleRequest request,
                                      Principal principal) {
        StaffUser member = get(id);
        if (member.getUsername().equals(principal.getName()) && request.role() != StaffUser.Role.OWNER) {
            throw new IllegalStateException("You cannot demote your own account — ask another owner");
        }
        if (member.getRole() == StaffUser.Role.OWNER && request.role() != StaffUser.Role.OWNER) {
            requireAnotherOwner(member);
        }
        member.setRole(request.role());
        staff.save(member);
        audit.record(principal, "staff.role", member.getFullName() + " is now " + request.role());
        return Map.of("id", member.getId(), "role", member.getRole());
    }

    public record PasswordRequest(@NotBlank @Size(min = 8) String password) {
    }

    @PatchMapping("/{id}/password")
    @PreAuthorize("hasAuthority('STAFF')")
    public Map<String, String> setPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest request,
                                           Principal principal) {
        StaffUser member = get(id);
        member.setPasswordHash(encoder.encode(request.password()));
        staff.save(member);
        audit.record(principal, "staff.password", "Reset the password for " + member.getFullName());
        return Map.of("message", member.getFullName() + "'s password was reset");
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('STAFF')")
    public Map<String, Object> toggle(@PathVariable Long id, Principal principal) {
        StaffUser member = get(id);
        if (member.getUsername().equals(principal.getName())) {
            throw new IllegalStateException("You cannot disable the account you are signed in with");
        }
        if (member.isActive() && member.getRole() == StaffUser.Role.OWNER) {
            requireAnotherOwner(member);
        }
        member.setActive(!member.isActive());
        staff.save(member);
        audit.record(principal, "staff.toggle",
                member.getFullName() + " is now " + (member.isActive() ? "active" : "disabled"));
        return Map.of("id", member.getId(), "active", member.isActive());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('STAFF')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Principal principal) {
        StaffUser member = get(id);
        if (member.getUsername().equals(principal.getName())) {
            throw new IllegalStateException("You cannot delete the account you are signed in with");
        }
        if (member.getRole() == StaffUser.Role.OWNER) {
            requireAnotherOwner(member);
        }
        audit.record(principal, "staff.delete", "Removed " + member.getFullName());
        staff.delete(member);
    }

    /**
     * Refuses to leave the system with no active owner. Without this, one
     * click could make the staff page permanently unreachable.
     */
    private void requireAnotherOwner(StaffUser about) {
        long owners = staff.findAll().stream()
                .filter(StaffUser::isActive)
                .filter(m -> m.getRole() == StaffUser.Role.OWNER)
                .filter(m -> !m.getId().equals(about.getId()))
                .count();
        if (owners == 0) {
            throw new IllegalStateException(
                    "That would leave nobody able to manage staff — promote another owner first");
        }
    }

    private StaffUser get(Long id) {
        return staff.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown staff account: " + id));
    }
}
