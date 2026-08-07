package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.service.AuthService;
import com.spalimited.hotspotbilling.service.WebAuthnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passkey enrolment and passwordless sign-in.
 *
 * <p>The register endpoints need a signed-in caller (see SecurityConfig,
 * which requires authentication for /register/** and /credentials/**); the
 * login endpoints are open, since signing in is exactly what has not happened
 * yet.
 */
@RestController
@RequestMapping("/api/auth/passkey")
@RequiredArgsConstructor
public class PasskeyController {

    private final WebAuthnService webAuthn;
    private final StaffUserRepository staff;
    private final AuthService authService;

    // --- Enrolment (signed in) ---

    @PostMapping("/register/start")
    public Map<String, Object> registerStart(Principal principal) {
        return webAuthn.startRegistration(me(principal));
    }

    @PostMapping("/register/finish")
    public Map<String, Object> registerFinish(@Valid @RequestBody WebAuthnService.RegistrationResponse body,
                                              Principal principal) {
        webAuthn.finishRegistration(me(principal), body);
        return Map.of("registered", true, "message", "Passkey saved. You can sign in with it next time.");
    }

    @GetMapping("/credentials")
    public List<Map<String, Object>> credentials(Principal principal) {
        return webAuthn.list(me(principal).getId());
    }

    @DeleteMapping("/credentials/{id}")
    public Map<String, Object> remove(@PathVariable Long id, Principal principal) {
        webAuthn.remove(me(principal), id);
        return Map.of("removed", true);
    }

    // --- Passwordless sign-in (not signed in) ---

    public record LoginStartRequest(@NotBlank String username) {
    }

    @PostMapping("/login/start")
    public Map<String, Object> loginStart(@Valid @RequestBody LoginStartRequest body) {
        return webAuthn.startAuthentication(body.username());
    }

    public record LoginFinishRequest(@NotBlank String username,
                                     @Valid WebAuthnService.AuthenticationResponse response) {
    }

    @PostMapping("/login/finish")
    public ResponseEntity<Map<String, Object>> loginFinish(@Valid @RequestBody LoginFinishRequest body,
                                                          HttpServletRequest http) {
        StaffUser user = webAuthn.finishAuthentication(body.username(), body.response());
        AuthService.Session session = authService.startSession(
                user, http.getHeader("User-Agent"), clientIp(http));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", session.token());
        out.put("expiresAt", session.expiresAt());
        out.put("username", user.getUsername());
        out.put("fullName", user.getFullName());
        out.put("role", user.getRole());
        out.put("permissions", user.getPermissions().stream().sorted().toList());
        out.put("twoFactor", user.isTotpEnabled());
        return ResponseEntity.ok(out);
    }

    private StaffUser me(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Sign in first.");
        }
        return staff.findByUsernameAndActiveTrue(principal.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "The break-glass config account cannot hold a passkey. Create a named login and use that."));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
