package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a signup into a provisioning tenant. Validation is strict because the
 * slug becomes a subdomain, a container name and a shell argument: only lower
 * alphanumerics and single dashes, nothing reserved, so it can never inject a
 * flag or a shell metacharacter downstream.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignupService {

    private final TenantRepository tenants;
    private final ProvisioningWorker worker;
    private final MailService mailService;
    private final PendingPasswords pendingPasswords;

    /**
     * Whether a signup has to prove its email before anything is built.
     *
     * <p>Off by default, which keeps today's behaviour. On, it is the abuse
     * guard the multi-tenancy plan asks for: a container and a database per junk
     * signup is expensive, and a public form invites exactly that.
     */
    @Value("${zidi.signup.require-email-verification:false}")
    private boolean requireVerification;

    /**
     * Where the verification link points. Must be the address the outside world
     * reaches the control plane on, which is not something the process can work
     * out for itself when it sits behind a proxy.
     */
    @Value("${zidi.public-url:}")
    private String publicUrl;

    /** How long a verification link is good for. Matches PendingPasswords. */
    private static final Duration VERIFICATION_TTL = Duration.ofHours(24);

    @Value("${zidi.mail.enabled:false}")
    private boolean mailEnabled;

    /**
     * Says out loud when the two settings disagree.
     *
     * <p>Verification on with mail off is a dev configuration. In production it
     * means every signup waits forever for a link that only ever reached a log
     * file, and the symptom -- accounts stuck at AWAITING_EMAIL -- gives no hint
     * why. One line at startup is cheaper than that conversation.
     */
    @jakarta.annotation.PostConstruct
    void warnAboutMail() {
        if (requireVerification && !mailEnabled) {
            log.warn("zidi.signup.require-email-verification is on but zidi.mail.enabled is off — "
                    + "confirmation links will only appear in this log, so no signup can "
                    + "complete without somebody reading it. Set MAIL_HOST for production.");
        }
        if (requireVerification && (publicUrl == null || publicUrl.isBlank())) {
            log.warn("zidi.signup.require-email-verification is on but zidi.public-url is not set — "
                    + "confirmation links will be built from the base domain, which may not be "
                    + "where this control plane actually answers.");
        }
    }

    @Value("${zidi.base-domain}")
    private String baseDomain;

    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
    private static final Set<String> RESERVED = Set.of(
            "www", "api", "admin", "app", "mail", "demo", "status", "billing",
            "dashboard", "portal", "zidi", "test", "staging", "control", "edge");

    public record SignupRequest(String businessName, String slug, String ownerName,
                                String ownerEmail, String password) {
    }

    // Not @Transactional: the tenant must be committed before the async worker
    // (a separate thread, separate transaction) tries to load it, or it races
    // and finds nothing. repository.save commits on its own.
    public Tenant signup(SignupRequest req) {
        String slug = req.slug() == null ? "" : req.slug().trim().toLowerCase();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "Choose a name of 2–40 lowercase letters, digits and dashes (not starting or ending with a dash).");
        }
        if (RESERVED.contains(slug)) {
            throw new IllegalArgumentException("That name is reserved — pick another.");
        }
        if (tenants.existsBySlug(slug)) {
            throw new IllegalArgumentException("That name is taken — pick another.");
        }
        String subdomain = slug + "." + baseDomain;
        if (tenants.existsBySubdomain(subdomain)) {
            throw new IllegalArgumentException("That address is taken — pick another.");
        }
        // One account per email — a repeat signup should sign in instead. Keeps
        // the platform-admin list clean and stops the 14-day trial being reset
        // over and over from the same address.
        String email = req.ownerEmail() == null ? "" : req.ownerEmail().trim().toLowerCase();
        if (tenants.existsByOwnerEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists — sign in instead.");
        }

        Tenant tenant = tenants.save(Tenant.builder()
                .slug(slug)
                .subdomain(subdomain)
                .businessName(req.businessName() == null ? slug : req.businessName().trim())
                .ownerName(req.ownerName() == null ? null : req.ownerName().trim())
                .ownerEmail(email)
                .status(requireVerification
                        ? Tenant.Status.AWAITING_EMAIL : Tenant.Status.PROVISIONING)
                .statusDetail(requireVerification
                        ? "Check your email to confirm your address."
                        : "Setting up your account…")
                .verificationToken(requireVerification ? newToken() : null)
                .verificationSentAt(requireVerification ? Instant.now() : null)
                .verifiedAt(requireVerification ? null : Instant.now())
                .build());

        if (!requireVerification) {
            worker.provision(tenant.getId(), req.password());
            return tenant;
        }

        // Held in memory rather than in the registry: see PendingPasswords for
        // why a plaintext password in the database would be the worse option.
        pendingPasswords.put(tenant.getId(), req.password());

        if (!mailService.sendVerification(tenant, verificationLink(tenant))) {
            // Verification is required and the email did not go out, so this
            // account can never be completed. Deleting it frees the slug and the
            // email address instead of leaving a permanent squatter, and the
            // person is told the truth rather than being left waiting.
            tenants.delete(tenant);
            pendingPasswords.forget(tenant.getId());
            throw new IllegalStateException(
                    "We could not send the confirmation email, so the account was not created. "
                            + "Please try again shortly.");
        }
        return tenant;
    }

    /**
     * Re-run provisioning for a tenant that failed (admin action). The owner's
     * password isn't stored, so a retry provisions without it — the script then
     * generates one, which the admin reads from the logs.
     */
    /**
     * Completes a signup whose owner has clicked the link.
     *
     * <p>The token is cleared as it is used, so a link works once — a forwarded
     * or logged email cannot re-trigger provisioning later.
     */
    public Tenant verify(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("That confirmation link is not valid.");
        }
        Tenant tenant = tenants.findByVerificationToken(token)
                // Deliberately the same message as an expired one. Distinguishing
                // "no such token" from "expired token" tells somebody guessing
                // tokens when they have found a real one.
                .orElseThrow(() -> new IllegalArgumentException(
                        "That confirmation link is not valid or has already been used."));

        if (tenant.getVerificationSentAt() != null
                && tenant.getVerificationSentAt().plus(VERIFICATION_TTL).isBefore(Instant.now())) {
            throw new IllegalArgumentException(
                    "That confirmation link has expired. Sign up again and we will send a new one.");
        }

        tenant.setVerifiedAt(Instant.now());
        tenant.setVerificationToken(null);
        tenant.setStatus(Tenant.Status.PROVISIONING);
        tenant.setStatusDetail("Setting up your account…");
        tenants.save(tenant);

        // Absent after a restart, which is fine: the provisioner generates one,
        // exactly as it does for an admin retry.
        worker.provision(tenant.getId(), pendingPasswords.take(tenant.getId()).orElse(null));
        return tenant;
    }

    /** The link that lands in the owner's inbox. */
    private String verificationLink(Tenant tenant) {
        String base = publicUrl == null || publicUrl.isBlank()
                ? "https://" + baseDomain : publicUrl.replaceAll("/+$", "");
        return base + "/api/signup/verify?token=" + tenant.getVerificationToken();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void retry(String slug) {
        Tenant tenant = tenants.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("No such tenant"));
        tenant.setStatus(Tenant.Status.PROVISIONING);
        tenant.setStatusDetail("Retrying…");
        tenants.save(tenant);
        worker.provision(tenant.getId(), null);
    }
}
