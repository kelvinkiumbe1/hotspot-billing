package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.domain.WebAuthnCredential;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.repository.WebAuthnCredentialRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.*;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.server.ServerProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WebAuthn ceremony for staff passkeys, wrapped around webauthn4j so the
 * COSE/CBOR crypto is a vetted library's problem rather than ours.
 *
 * <p>Two flows: registration (a signed-in person enrolling a device) and
 * authentication (signing in with that device). Each is a start call that
 * hands the browser a challenge, and a finish call that verifies what the
 * authenticator signed. Challenges live in memory for a couple of minutes —
 * a passkey ceremony is a single round trip, not something to persist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebAuthnService {

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(3);
    private static final long TIMEOUT_MS = 120_000L;

    private final StaffUserRepository staff;
    private final WebAuthnCredentialRepository credentials;

    @Value("${webauthn.rp-id}")
    private String rpId;
    @Value("${webauthn.rp-name}")
    private String rpName;
    @Value("${webauthn.origins}")
    private String originsCsv;
    @Value("${webauthn.enrollment-required}")
    private boolean enrollmentRequired;

    private final WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final AttestationObjectConverter attestationObjectConverter =
            new AttestationObjectConverter(objectConverter);
    private final SecureRandom random = new SecureRandom();

    private record Pending(byte[] challenge, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private Set<Origin> origins() {
        Set<Origin> set = new LinkedHashSet<>();
        for (String o : originsCsv.split(",")) {
            String trimmed = o.trim();
            if (!trimmed.isEmpty()) {
                set.add(Origin.create(trimmed));
            }
        }
        return set;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] unb64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private byte[] newChallenge(String key) {
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        pending.put(key, new Pending(challenge, Instant.now().plus(CHALLENGE_TTL)));
        return challenge;
    }

    private byte[] takeChallenge(String key) {
        Pending p = pending.remove(key);
        if (p == null || p.expired()) {
            throw new IllegalStateException("This request has expired. Start again.");
        }
        return p.challenge();
    }

    public boolean enrollmentRequiredFor(StaffUser user) {
        return enrollmentRequired && credentials.countByStaffUserId(user.getId()) == 0;
    }

    public boolean hasPasskeys(Long staffUserId) {
        return credentials.countByStaffUserId(staffUserId) > 0;
    }

    // --- Registration (signed-in) ---

    @Transactional
    public Map<String, Object> startRegistration(StaffUser user) {
        if (user.getWebauthnUserHandle() == null) {
            byte[] handle = new byte[32];
            random.nextBytes(handle);
            user.setWebauthnUserHandle(b64(handle));
            staff.save(user);
        }
        byte[] challenge = newChallenge("r:" + user.getId());

        List<Map<String, Object>> exclude = credentials.findByStaffUserId(user.getId()).stream()
                .map(c -> Map.<String, Object>of("type", "public-key", "id", c.getCredentialId()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("challenge", b64(challenge));
        out.put("rp", Map.of("id", rpId, "name", rpName));
        out.put("user", Map.of(
                "id", user.getWebauthnUserHandle(),
                "name", user.getUsername(),
                "displayName", user.getFullName() == null ? user.getUsername() : user.getFullName()));
        out.put("pubKeyCredParams", List.of(
                Map.of("type", "public-key", "alg", -7),    // ES256
                Map.of("type", "public-key", "alg", -257))); // RS256
        out.put("timeout", TIMEOUT_MS);
        out.put("attestation", "none");
        out.put("authenticatorSelection", Map.of(
                "residentKey", "preferred",
                "userVerification", "preferred"));
        out.put("excludeCredentials", exclude);
        return out;
    }

    public record RegistrationResponse(String id, String clientDataJSON, String attestationObject, String label) {
    }

    @Transactional
    public void finishRegistration(StaffUser user, RegistrationResponse response) {
        byte[] challenge = takeChallenge("r:" + user.getId());

        RegistrationData data = webAuthnManager.parse(
                new RegistrationRequest(unb64(response.attestationObject()), unb64(response.clientDataJSON())));
        ServerProperty serverProperty =
                new ServerProperty(origins(), rpId, new DefaultChallenge(challenge), null);
        // userVerificationRequired false (we ask "preferred"), userPresenceRequired true.
        webAuthnManager.verify(data, new RegistrationParameters(serverProperty, false, true));

        var attested = data.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        String credentialId = b64(attested.getCredentialId());
        if (credentials.findByCredentialId(credentialId).isPresent()) {
            throw new IllegalStateException("That passkey is already registered.");
        }
        credentials.save(WebAuthnCredential.builder()
                .staffUserId(user.getId())
                .credentialId(credentialId)
                .attestationObject(attestationObjectConverter.convertToBytes(data.getAttestationObject()))
                .signCount(data.getAttestationObject().getAuthenticatorData().getSignCount())
                .label(response.label() == null || response.label().isBlank() ? "Passkey" : response.label().trim())
                .build());
        log.info("Passkey enrolled for {}", user.getUsername());
    }

    // --- Authentication (not signed in) ---

    @Transactional(readOnly = true)
    public Map<String, Object> startAuthentication(String username) {
        StaffUser user = staff.findByUsernameAndActiveTrue(username == null ? "" : username.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("No passkey is set up for this account."));
        List<WebAuthnCredential> creds = credentials.findByStaffUserId(user.getId());
        if (creds.isEmpty()) {
            throw new IllegalArgumentException("No passkey is set up for this account.");
        }
        byte[] challenge = newChallenge("a:" + user.getUsername());

        List<Map<String, Object>> allow = creds.stream()
                .map(c -> Map.<String, Object>of("type", "public-key", "id", c.getCredentialId()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("challenge", b64(challenge));
        out.put("rpId", rpId);
        out.put("timeout", TIMEOUT_MS);
        out.put("userVerification", "preferred");
        out.put("allowCredentials", allow);
        return out;
    }

    public record AuthenticationResponse(String id, String clientDataJSON, String authenticatorData,
                                         String signature, String userHandle) {
    }

    /** Verifies the assertion and returns the staff member it belongs to. */
    @Transactional
    public StaffUser finishAuthentication(String username, AuthenticationResponse response) {
        String name = username == null ? "" : username.trim().toLowerCase();
        byte[] challenge = takeChallenge("a:" + name);

        WebAuthnCredential cred = credentials.findByCredentialId(response.id())
                .orElseThrow(() -> new IllegalArgumentException("Unknown passkey."));
        StaffUser user = staff.findByUsernameAndActiveTrue(name)
                .orElseThrow(() -> new IllegalArgumentException("No passkey is set up for this account."));
        if (!cred.getStaffUserId().equals(user.getId())) {
            // The signed credential does not belong to the named account.
            throw new IllegalArgumentException("That passkey does not match this account.");
        }

        AttestationObject attestationObject = attestationObjectConverter.convert(cred.getAttestationObject());
        CredentialRecordImpl record = new CredentialRecordImpl(attestationObject, null, null, null);

        AuthenticationData authData = webAuthnManager.parse(new AuthenticationRequest(
                unb64(response.id()),
                response.userHandle() == null || response.userHandle().isBlank() ? null : unb64(response.userHandle()),
                unb64(response.authenticatorData()),
                unb64(response.clientDataJSON()),
                unb64(response.signature())));
        ServerProperty serverProperty =
                new ServerProperty(origins(), rpId, new DefaultChallenge(challenge), null);
        webAuthnManager.verify(authData, new AuthenticationParameters(
                serverProperty, record, List.of(unb64(response.id())), false, true));

        cred.setSignCount(authData.getAuthenticatorData().getSignCount());
        cred.setLastUsedAt(Instant.now());
        credentials.save(cred);
        return user;
    }

    // --- Management ---

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long staffUserId) {
        return credentials.findByStaffUserId(staffUserId).stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "label", c.getLabel() == null ? "Passkey" : c.getLabel(),
                        "createdAt", c.getCreatedAt(),
                        "lastUsedAt", c.getLastUsedAt() == null ? "" : c.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public void remove(StaffUser user, Long credentialRowId) {
        WebAuthnCredential cred = credentials.findById(credentialRowId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown passkey."));
        if (!cred.getStaffUserId().equals(user.getId())) {
            throw new IllegalStateException("That passkey is not yours to remove.");
        }
        credentials.delete(cred);
    }

    /** Clears every passkey for a user — used when an owner resets a password. */
    @Transactional
    public void clearForUser(Long staffUserId) {
        credentials.deleteByStaffUserId(staffUserId);
    }
}
