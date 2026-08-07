package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.service.AuthService;
import com.spalimited.hotspotbilling.service.PasswordPolicy;
import com.spalimited.hotspotbilling.service.TotpService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.WebAuthnService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Signing in, signing out, and setting up two-factor. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final StaffUserRepository staff;
    private final TotpService totp;
    private final PasswordEncoder encoder;
    private final PortalSettingsService portalSettings;
    private final WebAuthnService webAuthn;

    public record LoginRequest(@NotBlank String username, @NotBlank String password, String code) {
    }

    /**
     * Exchanges a password — and a one-time code where the account has one —
     * for a session token.
     *
     * <p>A password that is right but missing its code answers 428 rather
     * than 401, so the sign-in screen knows to ask for the code instead of
     * telling the person their password was wrong.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest http) {
        try {
            AuthService.Session session = authService.signIn(
                    request.username(), request.password(), request.code(),
                    http.getHeader("User-Agent"), clientIp(http));

            StaffUser user = session.user();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", session.token());
            out.put("expiresAt", session.expiresAt());
            out.put("username", user.getUsername());
            out.put("fullName", user.getFullName());
            out.put("role", user.getRole());
            out.put("permissions", user.getPermissions().stream().sorted().toList());
            out.put("twoFactor", user.isTotpEnabled());
            // The sign-in screen uses these to run the passkey flow: whether
            // this account already has one, and whether policy makes enrolling
            // one mandatory before it reaches the dashboard.
            out.put("hasPasskeys", webAuthn.hasPasskeys(user.getId()));
            out.put("passkeyEnrollmentRequired", webAuthn.enrollmentRequiredFor(user));
            return ResponseEntity.ok(out);

        } catch (AuthService.TotpRequiredException e) {
            return ResponseEntity.status(428).body(Map.of(
                    "message", e.getMessage(), "codeRequired", true));
        }
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            authService.signOut(header.substring(7).trim());
        }
        return Map.of("message", "Signed out");
    }

    /** The rules, so a form can show them before anyone types. */
    @GetMapping("/password-rules")
    public Map<String, Object> passwordRules() {
        return Map.of("rules", PasswordPolicy.rules());
    }

    // --- Two-factor setup, for the signed-in person only ---

    /**
     * Starts setup: a secret and the URI an authenticator app scans. It is
     * not switched on until a code proves the app actually has it — enabling
     * on generation would lock people out of their own accounts whenever a
     * scan silently failed.
     */
    @PostMapping("/2fa/start")
    public Map<String, Object> startTwoFactor(Principal principal) {
        StaffUser user = me(principal);
        if (user.isTotpEnabled()) {
            throw new IllegalStateException("Two-factor is already on for this account");
        }
        String secret = totp.newSecret();
        user.setTotpSecret(secret);
        staff.save(user);

        String issuer = portalSettings.settings().getBusinessName();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secret", secret);
        out.put("uri", totp.provisioningUri(secret, user.getUsername(),
                issuer == null || issuer.isBlank() ? "SPA WiFi" : issuer));
        out.put("message", "Scan this in your authenticator app, then enter the 6-digit code to switch it on.");
        return out;
    }

    public record CodeRequest(@NotBlank String code) {
    }

    @PostMapping("/2fa/confirm")
    public Map<String, Object> confirmTwoFactor(@Valid @RequestBody CodeRequest request,
                                                Principal principal) {
        StaffUser user = me(principal);
        if (user.getTotpSecret() == null) {
            throw new IllegalStateException("Start the setup first");
        }
        if (!totp.verify(user.getTotpSecret(), request.code())) {
            throw new IllegalArgumentException(
                    "That code is not right. Check the time on your phone is correct and try the next one.");
        }
        user.setTotpEnabled(true);
        user.setTotpConfirmedAt(java.time.Instant.now());
        staff.save(user);
        return Map.of("enabled", true,
                "message", "Two-factor is on. You will need your authenticator app to sign in from now on.");
    }

    public record DisableRequest(@NotBlank String password, @NotBlank String code) {
    }

    /** Turning it off needs both factors, or losing a session would be enough. */
    @PostMapping("/2fa/disable")
    public Map<String, Object> disableTwoFactor(@Valid @RequestBody DisableRequest request,
                                                Principal principal) {
        StaffUser user = me(principal);
        if (!user.isTotpEnabled()) {
            throw new IllegalStateException("Two-factor is not on for this account");
        }
        if (!encoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("That is not your password");
        }
        if (!totp.verify(user.getTotpSecret(), request.code())) {
            throw new IllegalArgumentException("That code is not right");
        }
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        user.setTotpConfirmedAt(null);
        staff.save(user);
        return Map.of("enabled", false, "message", "Two-factor is off.");
    }

    private StaffUser me(Principal principal) {
        return staff.findByUsernameAndActiveTrue(principal.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "The fallback account from the config file cannot use two-factor. "
                                + "Create a named login and use that."));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
