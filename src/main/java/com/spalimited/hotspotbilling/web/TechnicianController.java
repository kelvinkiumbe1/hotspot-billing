package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.service.FieldChatPin;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin management of field technician accounts (HTTP Basic, ADMIN role). */
@RestController
@RequestMapping("/api/admin/technicians")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('STAFF')")
public class TechnicianController {

    private final TechnicianRepository technicians;
    private final PasswordEncoder passwordEncoder;
    private final FieldChatPin fieldChatPin;

    public record PinRequest(String pin) {
    }

    /**
     * Sets a technician's WhatsApp field PIN.
     *
     * <p>Office-side on purpose: if a technician could choose their own on first
     * contact then whoever is holding the phone would choose it, and the PIN
     * would gate nothing. Read it to them, do not send it to the same handset.
     */
    @PostMapping("/{id}/chat-pin")
    public Map<String, Object> setChatPin(@PathVariable Long id, @RequestBody PinRequest body,
                                          Principal principal) {
        String who = principal != null ? principal.getName() : "system";
        fieldChatPin.setPin(id, body.pin(), who);
        return Map.of("ok", true,
                "message", "Field PIN set. Read it to them — do not text it to that phone.");
    }

    /** Takes the PIN away, which shuts that technician out of the field bot. */
    @DeleteMapping("/{id}/chat-pin")
    public Map<String, Object> clearChatPin(@PathVariable Long id, Principal principal) {
        String who = principal != null ? principal.getName() : "system";
        fieldChatPin.clearPin(id, who);
        return Map.of("ok", true, "message", "Field PIN removed. They can no longer use the chat.");
    }

    @GetMapping
    public List<Technician> all() {
        return technicians.findAllByOrderByCreatedAtAsc();
    }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,30}",
                    message = "Username must be 3-30 letters, digits, dots, dashes or underscores")
            String username,
            @NotBlank @Size(min = 6, message = "Password must be at least 6 characters") String password,
            @NotBlank String fullName,
            String phoneNumber,
            Boolean canVouchers,
            Boolean canPppoe) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Technician create(@Valid @RequestBody CreateRequest request) {
        technicians.findByUsername(request.username()).ifPresent(t -> {
            throw new IllegalArgumentException("Username already taken: " + request.username());
        });
        return technicians.save(Technician.builder()
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .phoneNumber(request.phoneNumber())
                .canVouchers(request.canVouchers() == null || request.canVouchers())
                .canPppoe(Boolean.TRUE.equals(request.canPppoe()))
                .build());
    }

    public record PermissionsRequest(Boolean canVouchers, Boolean canPppoe) {
    }

    /** Grants or removes what the technician may do in Field Connect. */
    @PatchMapping("/{id}/permissions")
    public Technician setPermissions(@PathVariable Long id, @RequestBody PermissionsRequest request) {
        Technician tech = technicians.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown technician: " + id));
        if (request.canVouchers() != null) {
            tech.setCanVouchers(request.canVouchers());
        }
        if (request.canPppoe() != null) {
            tech.setCanPppoe(request.canPppoe());
        }
        return technicians.save(tech);
    }

    @PatchMapping("/{id}/toggle")
    public Technician toggle(@PathVariable Long id) {
        Technician tech = technicians.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown technician: " + id));
        tech.setActive(!tech.isActive());
        return technicians.save(tech);
    }

    /**
     * Permanently removes a technician account. Their message history,
     * task notes and payout records are kept (they reference the username,
     * not the account), but the person can no longer log in.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        Technician tech = technicians.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown technician: " + id));
        technicians.delete(tech);
    }

    public record PasswordRequest(@NotBlank @Size(min = 6) String password) {
    }

    @PatchMapping("/{id}/password")
    public Technician resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest request) {
        Technician tech = technicians.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown technician: " + id));
        tech.setPasswordHash(passwordEncoder.encode(request.password()));
        return technicians.save(tech);
    }
}
