package com.spalimited.controlplane;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    /**
     * The link from the owner's email.
     *
     * <p>Answers with a page rather than JSON, because the thing on the other
     * end of this URL is a person in a mail client, not a script. It is the one
     * endpoint here a human reads directly.
     *
     * <p>Always 200, even when the token is wrong. A 404 in a browser is a
     * default error page and tells somebody nothing about what to do next; the
     * page below says whether it worked and what to try instead.
     */
    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE)
    public String verify(@RequestParam(required = false) String token) {
        try {
            Tenant tenant = signupService.verify(token);
            return page("Email confirmed",
                    "Thanks — we are setting up " + escape(tenant.getBusinessName()) + " now. "
                            + "This takes a minute or two, and we will email you the moment it "
                            + "is ready.",
                    escape(tenant.getUrl()));
        } catch (RuntimeException e) {
            // The service already writes messages meant for a person to read.
            return page("That link did not work", escape(e.getMessage()), null);
        }
    }

    /**
     * A whole page in a string, with no template engine.
     *
     * <p>The control plane serves three static files and this. Adding Thymeleaf
     * to render two paragraphs would be a dependency, a directory and a build
     * step for something a person sees once.
     */
    private static String page(String heading, String body, String url) {
        return """
                <!doctype html>
                <html lang="en"><head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Zidi</title>
                <style>
                  :root { color-scheme: light dark; }
                  body { margin:0; min-height:100vh; display:grid; place-items:center;
                         font:16px/1.6 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
                         background:#0b1220; color:#e8ecf4; padding:24px; }
                  .card { max-width:34rem; background:#131c2f; border:1px solid #26324a;
                          border-radius:16px; padding:32px; }
                  h1 { margin:0 0 12px; font-size:1.5rem; }
                  p { margin:0 0 16px; color:#aab4c8; }
                  a { display:inline-block; margin-top:8px; padding:10px 16px; border-radius:10px;
                      background:#f0b429; color:#1a1206; font-weight:600; text-decoration:none; }
                </style>
                </head><body><div class="card">
                <h1>%s</h1>
                <p>%s</p>
                %s
                </div></body></html>
                """.formatted(escape(heading), body,
                        url == null ? "" : "<a href=\"" + url + "/admin\">Go to your account</a>");
    }

    /** No user-supplied value reaches that page unescaped. */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
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
        // So the signup page can say "check your email" rather than spinning on
        // a provisioning poll that will not start until somebody clicks a link.
        out.put("awaitingEmail", t.getStatus() == Tenant.Status.AWAITING_EMAIL);
        return out;
    }
}
