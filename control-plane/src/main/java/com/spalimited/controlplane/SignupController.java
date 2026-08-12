package com.spalimited.controlplane;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public self-service signup and the status poll the "setting up" page uses. */
@RestController
@RequestMapping("/api/signup")
@RequiredArgsConstructor
public class SignupController {

    private final SignupService signupService;
    private final TenantRepository tenants;
    private final SignupRateLimiter rateLimiter;

    public record SignupBody(
            @NotBlank @Size(max = 160) String businessName,
            @NotBlank @Size(min = 2, max = 40) String slug,
            @Size(max = 160) String ownerName,
            @NotBlank @Email @Size(max = 160) String ownerEmail,
            @NotBlank @Size(min = 8, max = 100, message = "Choose a password of at least 8 characters") String password) {
    }

    @PostMapping
    public Map<String, Object> signup(@Valid @RequestBody SignupBody body, HttpServletRequest http) {
        if (!rateLimiter.allow(clientIp(http))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many signups from here — please try again later.");
        }
        Tenant tenant = signupService.signup(new SignupService.SignupRequest(
                body.businessName(), body.slug(), body.ownerName(), body.ownerEmail(), body.password()));
        return view(tenant);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/{slug}/status")
    public Map<String, Object> status(@PathVariable String slug) {
        Tenant tenant = tenants.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("No such account"));
        return view(tenant);
    }

    private static Map<String, Object> view(Tenant t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", t.getSlug());
        out.put("subdomain", t.getSubdomain());
        out.put("url", t.getUrl());
        out.put("status", t.getStatus().name());
        out.put("statusDetail", t.getStatusDetail());
        return out;
    }
}
