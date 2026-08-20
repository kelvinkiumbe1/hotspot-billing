package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.PhoneVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Confirming a customer's phone number.
 *
 * <p>Public, because the person proving their number is not signed in to
 * anything. That makes rate limiting the whole of the security story, and it
 * lives in the service rather than here so a second caller cannot bypass it.
 */
@RestController
@RequestMapping("/api/verify")
@RequiredArgsConstructor
public class PhoneVerificationController {

    private final PhoneVerificationService verification;

    public record RequestBody_(@NotBlank @Size(max = 32) String phoneNumber,
                               @Size(max = 32) String purpose) {
    }

    @PostMapping("/request")
    public Map<String, Object> request(@Valid @RequestBody RequestBody_ body,
                                       HttpServletRequest http) {
        PhoneVerificationService.Requested r = verification.request(
                body.phoneNumber(), body.purpose(), clientIp(http));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", r.sent());
        out.put("message", r.message());
        out.put("expiresAt", r.expiresAt());
        return out;
    }

    public record ConfirmBody(@NotBlank @Size(max = 32) String phoneNumber,
                              @Size(max = 32) String purpose,
                              @NotBlank @Size(max = 12) String code) {
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@Valid @RequestBody ConfirmBody body) {
        PhoneVerificationService.Checked c = verification.verify(
                body.phoneNumber(), body.purpose(), body.code());
        return Map.of("verified", c.verified(), "message", c.message());
    }

    /**
     * Behind the reverse proxy the caller's real address is in X-Forwarded-For.
     * Without it every request looks like it came from the proxy and the
     * per-address limit protects nothing.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
