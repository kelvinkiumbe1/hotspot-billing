package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.service.ApiTokenService;
import com.spalimited.hotspotbilling.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/** Developer settings: personal access tokens for the REST API. Owner-only. */
@RestController
@RequestMapping("/api/admin/api-tokens")
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenService apiTokens;
    private final StaffUserRepository staff;
    private final AuditService audit;

    @GetMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    public List<Map<String, Object>> list() {
        return apiTokens.list();
    }

    public record CreateRequest(@NotBlank @Size(max = 120) String name) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SETTINGS')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Created create(@Valid @RequestBody CreateRequest request, Principal principal) {
        StaffUser owner = staff.findByUsernameAndActiveTrue(principal.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "The break-glass config account can't own an API token — create a named login and use that."));
        ApiTokenService.Created created = apiTokens.create(request.name(), owner);
        audit.record(principal, "apitoken.create", "Created API token '" + created.name() + "'");
        return created;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SETTINGS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id, Principal principal) {
        apiTokens.revoke(id);
        audit.record(principal, "apitoken.revoke", "Revoked API token #" + id);
    }
}
